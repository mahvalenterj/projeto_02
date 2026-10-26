package br.gov.saude.upa.infrastructure.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fluxo inteiro com Postgres real e WireMock nos dois canais: marcar indisponível, outbox,
 * despachante, avisos. Contexto Spring PRÓPRIO (as portas do WireMock são dinâmicas). O relógio é
 * fixo e a concorrência do lote é 1, para a ordem dos envios ser a da seleção. Dados sintéticos.
 */
class AvisoDePontaAPontaIntegracaoTest extends PostgresIntegrationTest {

  private static final Instant AGORA = Instant.parse("2026-10-11T15:00:00Z");
  private static final Instant PRIMEIRO_EXAME = Instant.parse("2026-10-11T18:00:00Z");
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final Pattern DESTINATARIO = Pattern.compile("\"destinatario\":\"([^\"]+)\"");
  private static final String ACEITE_SMS = "{\"id\":\"msg-1\",\"situacao\":\"ACEITA\"}";
  private static final String ACEITE_EMAIL = "{\"id\":\"mail-1\",\"situacao\":\"ACEITO\"}";

  private static final WireMockServer SMS =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  private static final WireMockServer EMAIL =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    SMS.start();
    EMAIL.start();
  }

  @DynamicPropertySource
  static void canais(DynamicPropertyRegistry registro) {
    registro.add("upa.canais.sms.base-url", SMS::baseUrl);
    registro.add("upa.canais.email.base-url", EMAIL::baseUrl);
    registro.add("upa.canais.sms.retry.tentativas", () -> "2");
    registro.add("upa.canais.sms.retry.espera-inicial", () -> "PT0.01S");
    registro.add("upa.canais.email.retry.espera-inicial", () -> "PT0.01S");
    registro.add("upa.aviso.concorrencia-maxima", () -> "1");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RelogioFixo {

    @Bean
    @Primary
    Clock relogioDeTeste() {
      return Clock.fixed(AGORA, ZoneOffset.UTC);
    }
  }

  @AfterAll
  static void desligarProvedores() {
    SMS.stop();
    EMAIL.stop();
  }

  @Autowired private MarcarEquipamentoIndisponivel marcarIndisponivel;
  @Autowired private MarcarEquipamentoDisponivel marcarDisponivel;
  @Autowired private DespachanteDoOutbox despachante;
  @Autowired private JdbcTemplate jdbc;

  private UUID upa;
  private UUID tomografo;
  private int sequencia;
  private final List<UUID> eventos = new ArrayList<>();
  private final List<UUID> pacientes = new ArrayList<>();

  @BeforeEach
  void prepararCenario() {
    SMS.resetAll();
    EMAIL.resetAll();
    SMS.stubFor(
        post(urlPathEqualTo("/v1/mensagens"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ACEITE_SMS)));
    EMAIL.stubFor(
        post(urlPathEqualTo("/v1/emails"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ACEITE_EMAIL)));
    upa = UUID.randomUUID();
    tomografo = UUID.randomUUID();
    jdbc.update("INSERT INTO upa (id, nome) VALUES (?, ?)", upa, "UPA Sintetica");
    jdbc.update(
        "INSERT INTO equipamento (id, upa_id, tipo, status) VALUES (?, ?, 'TOMOGRAFO',"
            + " 'DISPONIVEL')",
        tomografo,
        upa);
    jdbc.update(
        "INSERT INTO upa_endereco_alternativo (upa_id, tipo, texto) VALUES (?, 'TOMOGRAFO', ?)",
        upa,
        "UPA Vizinha Sintetica, Rua Ficticia 1");
  }

  /** Apaga só o que este teste criou, por ids próprios, para não vazar para as outras classes. */
  @AfterEach
  void limparOQueOTesteCriou() {
    for (UUID evento : eventos) {
      jdbc.update("DELETE FROM aviso_paciente WHERE evento_id = ?", evento);
      jdbc.update("DELETE FROM revisao_manual WHERE evento_id = ?", evento);
      jdbc.update("DELETE FROM outbox_evento WHERE id = ?", evento);
    }
    jdbc.update("DELETE FROM agendamento_exame WHERE upa_id = ?", upa);
    for (UUID paciente : pacientes) {
      jdbc.update("DELETE FROM paciente WHERE id = ?", paciente);
    }
    jdbc.update("DELETE FROM upa_endereco_alternativo WHERE upa_id = ?", upa);
    jdbc.update("DELETE FROM equipamento WHERE upa_id = ?", upa);
    jdbc.update("DELETE FROM upa WHERE id = ?", upa);
    eventos.clear();
    pacientes.clear();
  }

  // ---------- fixtures ----------

  private String telefone(int n) {
    return "+5521900000" + String.format("%03d", n);
  }

  private UUID paciente(String telefone, String email, int anoNascimento) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?, ?)",
        id,
        "Paciente Sintetico " + (++sequencia),
        telefone,
        email,
        java.sql.Date.valueOf(LocalDate.of(anoNascimento, 1, 1)));
    pacientes.add(id);
    return id;
  }

  private UUID agendamento(UUID paciente, long horasDepoisDoPrimeiro) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id, criado_em,"
            + " inicio_exame) VALUES (?, 'TOMOGRAFIA', ?, ?, ?, ?, ?)",
        id,
        paciente,
        upa,
        tomografo,
        Timestamp.from(CRIADO_EM),
        Timestamp.from(PRIMEIRO_EXAME.plusSeconds(3600 * horasDepoisDoPrimeiro)));
    return id;
  }

  private UUID indisponivel() {
    UUID evento = marcarIndisponivel.executar(upa, tomografo);
    eventos.add(evento);
    return evento;
  }

  /** Volta o evento a pendente, como depois de um lease expirado. */
  private void reentregar(UUID eventoId) {
    jdbc.update(
        "UPDATE outbox_evento SET situacao = 'PENDENTE', processado_em = NULL,"
            + " reivindicado_ate = NULL WHERE id = ?",
        eventoId);
  }

  // ---------- consultas ----------

  private String situacaoDoEvento(UUID eventoId) {
    return jdbc.queryForObject(
        "SELECT situacao FROM outbox_evento WHERE id = ?", String.class, eventoId);
  }

  private String statusDoAviso(UUID eventoId, UUID agendamento) {
    return jdbc.queryForObject(
        "SELECT status FROM aviso_paciente WHERE evento_id = ? AND agendamento_id = ?",
        String.class,
        eventoId,
        agendamento);
  }

  private String motivoDoCancelamento(UUID eventoId, UUID agendamento) {
    return jdbc.queryForObject(
        "SELECT motivo_cancelamento FROM aviso_paciente WHERE evento_id = ? AND agendamento_id"
            + " = ?",
        String.class,
        eventoId,
        agendamento);
  }

  private List<String> motivosDeRevisao(UUID eventoId, UUID agendamento) {
    return jdbc.queryForList(
        "SELECT motivo FROM revisao_manual WHERE evento_id = ? AND agendamento_id = ?",
        String.class,
        eventoId,
        agendamento);
  }

  /** Requisições na ordem em que chegaram (findAll devolve da mais antiga para a mais recente). */
  private static List<LoggedRequest> recebidas(WireMockServer servidor) {
    return new ArrayList<>(
        servidor.findAll(
            com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl())));
  }

  private static String destinatario(LoggedRequest requisicao) {
    Matcher m = DESTINATARIO.matcher(requisicao.getBodyAsString());
    assertThat(m.find()).isTrue();
    return m.group(1);
  }

  // ---------- testes ----------

  @Test
  void ca4_deveAvisarPorSmsNaOrdemDoExameEMarcarOEventoProcessado() {
    UUID p1 = paciente(telefone(1), "um@exemplo.invalid", 1980);
    UUID p2 = paciente(telefone(2), "dois@exemplo.invalid", 1980);
    UUID p3 = paciente(telefone(3), "tres@exemplo.invalid", 1980);
    // Gravados fora de ordem: a seleção ordena pelo início do exame.
    UUID a3 = agendamento(p3, 2);
    UUID a1 = agendamento(p1, 0);
    UUID a2 = agendamento(p2, 1);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(statusDoAviso(eventoId, a2)).isEqualTo("CONFIRMADO");
    assertThat(statusDoAviso(eventoId, a3)).isEqualTo("CONFIRMADO");
    List<LoggedRequest> sms = recebidas(SMS);
    assertThat(sms)
        .extracting(AvisoDePontaAPontaIntegracaoTest::destinatario)
        .containsExactly(telefone(1), telefone(2), telefone(3));
    assertThat(sms.stream().map(r -> r.getHeader("Idempotency-Key")))
        .containsExactly(
            eventoId + ":" + a1 + ":sms",
            eventoId + ":" + a2 + ":sms",
            eventoId + ":" + a3 + ":sms");
    assertThat(recebidas(EMAIL)).isEmpty();
  }

  @Test
  void ca8_naoDeveReenviarNemDuplicarQuandoOEventoConfirmadoForReentregue() {
    UUID p1 = paciente(telefone(11), "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);
    UUID eventoId = indisponivel();
    despachante.despachar();
    assertThat(recebidas(SMS)).hasSize(1);

    reentregar(eventoId);
    despachante.despachar();

    assertThat(recebidas(SMS)).hasSize(1);
    assertThat(recebidas(EMAIL)).isEmpty();
    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(motivosDeRevisao(eventoId, a1)).isEmpty();
  }

  @Test
  void ca8_naoDeveDuplicarARevisaoManualQuandoOEventoSemContatoForReentregue() {
    UUID semContato = paciente(null, null, 1980);
    UUID a1 = agendamento(semContato, 0);
    UUID eventoId = indisponivel();
    despachante.despachar();
    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("SEM_CONTATO");

    reentregar(eventoId);
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("SEM_CONTATO");
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).isEmpty();
    assertThat(recebidas(EMAIL)).isEmpty();
  }

  @Test
  void ca5_deveCairNoEmailQuandoOSmsFalha() {
    SMS.stubFor(post(urlPathEqualTo("/v1/mensagens")).willReturn(aResponse().withStatus(500)));
    UUID p1 = paciente(telefone(21), "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(motivosDeRevisao(eventoId, a1)).isEmpty();
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).hasSize(2);
    List<LoggedRequest> emails = recebidas(EMAIL);
    assertThat(emails).hasSize(1);
    assertThat(emails.get(0).getHeader("Idempotency-Key"))
        .isEqualTo(eventoId + ":" + a1 + ":email");
  }

  @Test
  void ca5_deveEncaminharACanaisEsgotadosQuandoSmsEEmailFalham() {
    SMS.stubFor(post(urlPathEqualTo("/v1/mensagens")).willReturn(aResponse().withStatus(500)));
    EMAIL.stubFor(post(urlPathEqualTo("/v1/emails")).willReturn(aResponse().withStatus(500)));
    UUID p1 = paciente(telefone(22), "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("CANAIS_ESGOTADOS");
    assertThat(statusDoAviso(eventoId, a1)).isNotEqualTo("CONFIRMADO");
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).isNotEmpty();
    assertThat(recebidas(EMAIL)).isNotEmpty();
  }

  @Test
  void ca7_deveUsarOEmailQuandoOPacienteNaoTemTelefone() {
    UUID p1 = paciente(null, "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(recebidas(SMS)).isEmpty();
    List<LoggedRequest> emails = recebidas(EMAIL);
    assertThat(emails).hasSize(1);
    assertThat(emails.get(0).getHeader("Idempotency-Key"))
        .isEqualTo(eventoId + ":" + a1 + ":email");
    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(motivosDeRevisao(eventoId, a1)).isEmpty();
  }

  @Test
  void ca7_deveEncaminharASemContatoSemChamarCanalNenhum() {
    UUID semContato = paciente(null, null, 1980);
    UUID a1 = agendamento(semContato, 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("SEM_CONTATO");
    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("PENDENTE");
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).isEmpty();
    assertThat(recebidas(EMAIL)).isEmpty();
  }

  @Test
  void dadoInconsistente_deveEncaminharARevisaoManualOAgendamentoOrfao() {
    UUID a1 = agendamento(UUID.randomUUID(), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("DADO_INCONSISTENTE");
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).isEmpty();
    assertThat(recebidas(EMAIL)).isEmpty();
  }

  @Test
  void ca9_deveCancelarOAvisoSemRevisaoManualQuandoOEquipamentoVoltaAntesDoEnvio() {
    UUID p1 = paciente(telefone(31), "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);

    UUID eventoId = indisponivel();
    marcarDisponivel.executar(upa, tomografo);
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CANCELADO");
    assertThat(motivoDoCancelamento(eventoId, a1)).isEqualTo("EQUIPAMENTO_RESTABELECIDO");
    assertThat(motivosDeRevisao(eventoId, a1)).isEmpty();
    assertThat(situacaoDoEvento(eventoId)).isEqualTo("PROCESSADO");
    assertThat(recebidas(SMS)).isEmpty();
    assertThat(recebidas(EMAIL)).isEmpty();
  }

  @Test
  void ca17_deveGerarNovoEventoENovoLoteQuandoOEquipamentoFicaIndisponivelDeNovo() {
    UUID p1 = paciente(telefone(41), "um@exemplo.invalid", 1980);
    UUID a1 = agendamento(p1, 0);

    UUID primeiro = indisponivel();
    despachante.despachar();
    marcarDisponivel.executar(upa, tomografo);
    UUID segundo = indisponivel();
    despachante.despachar();

    assertThat(segundo).isNotEqualTo(primeiro);
    assertThat(situacaoDoEvento(primeiro)).isEqualTo("PROCESSADO");
    assertThat(situacaoDoEvento(segundo)).isEqualTo("PROCESSADO");
    assertThat(statusDoAviso(primeiro, a1)).isEqualTo("CONFIRMADO");
    assertThat(statusDoAviso(segundo, a1)).isEqualTo("CONFIRMADO");
    assertThat(recebidas(SMS).stream().map(r -> r.getHeader("Idempotency-Key")))
        .containsExactly(primeiro + ":" + a1 + ":sms", segundo + ":" + a1 + ":sms");
  }
}
