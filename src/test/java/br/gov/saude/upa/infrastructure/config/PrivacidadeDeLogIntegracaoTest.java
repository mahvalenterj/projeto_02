package br.gov.saude.upa.infrastructure.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.DetectorDeDadoSensivel;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.github.tomakehurst.wiremock.http.Fault;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CA12: nenhum log, em nenhum fluxo do lote, carrega nome, telefone, e-mail, nascimento, tipo ou
 * horário do exame. Fluxo inteiro (marcar indisponível, outbox, despachante, canais no WireMock)
 * com o appender de captura no logger RAIZ: entram a aplicação, o Spring e o Hibernate; só a
 * infraestrutura de teste (WireMock, Testcontainers) fica fora. Dados sintéticos com marcadores
 * únicos. Contexto Spring compartilhado com o ponta a ponta ({@link
 * CanaisSimuladosIntegracaoTest}); relógio fixo.
 */
class PrivacidadeDeLogIntegracaoTest extends CanaisSimuladosIntegracaoTest {

  // 21:37 UTC = 18:37 em São Paulo: horário que não aparece por acaso em outro log.
  private static final Instant PRIMEIRO_EXAME = Instant.parse("2026-10-11T21:37:00Z");
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final String ACEITE_SMS = "{\"id\":\"msg-1\",\"situacao\":\"ACEITA\"}";
  private static final String ACEITE_EMAIL = "{\"id\":\"mail-1\",\"situacao\":\"ACEITO\"}";

  @Autowired private MarcarEquipamentoIndisponivel marcarIndisponivel;
  @Autowired private MarcarEquipamentoDisponivel marcarDisponivel;
  @Autowired private DespachanteDoOutbox despachante;
  @Autowired private JdbcTemplate jdbc;

  private final DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();
  private DetectorDeDadoSensivel detector;
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
    detector = new DetectorDeDadoSensivel();
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
    // Liga a captura só depois do contexto pronto: o que se inspeciona é o fluxo do lote.
    captura.ligarNaRaiz();
  }

  /** Apaga só o que este teste criou, por ids próprios, para não vazar para as outras classes. */
  @AfterEach
  void limparOQueOTesteCriou() {
    captura.desligar();
    captura.limpar();
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

  // ---------- fixtures (cada dado sintético entra no detector) ----------

  private UUID paciente(boolean comTelefone, boolean comEmail) {
    int n = ++sequencia;
    String nome = "Marcadora Quixabeira Nomeada " + n;
    String telefone = comTelefone ? "+55219876" + String.format("%05d", 40_000 + n) : null;
    String email = comEmail ? "marcador.privado." + n + "@exemplo.invalid" : null;
    LocalDate nascimento = LocalDate.of(1937, 4, 1).plusDays(41L * n);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?, ?)",
        id,
        nome,
        telefone,
        email,
        java.sql.Date.valueOf(nascimento));
    detector.paciente(nome, telefone, email, nascimento);
    pacientes.add(id);
    return id;
  }

  private UUID agendamento(UUID paciente, long horasDepoisDoPrimeiro) {
    UUID id = UUID.randomUUID();
    Instant inicio = PRIMEIRO_EXAME.plusSeconds(3600 * horasDepoisDoPrimeiro);
    jdbc.update(
        "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id, criado_em,"
            + " inicio_exame) VALUES (?, 'TOMOGRAFIA', ?, ?, ?, ?, ?)",
        id,
        paciente,
        upa,
        tomografo,
        Timestamp.from(CRIADO_EM),
        Timestamp.from(inicio));
    detector.exame("TOMOGRAFIA", inicio);
    return id;
  }

  private UUID indisponivel() {
    UUID evento = marcarIndisponivel.executar(upa, tomografo);
    eventos.add(evento);
    return evento;
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

  private List<String> motivosDeRevisao(UUID eventoId, UUID agendamento) {
    return jdbc.queryForList(
        "SELECT motivo FROM revisao_manual WHERE evento_id = ? AND agendamento_id = ?",
        String.class,
        eventoId,
        agendamento);
  }

  /**
   * A captura precisa ter visto o fluxo (senão "nenhum vazamento" não prova nada) e nenhum evento
   * pode ter dado de paciente.
   */
  private void semDadoDePacienteNoLog(String... trechosEsperados) {
    List<ILoggingEvent> capturados = captura.eventos();
    assertThat(detector.quantidadeDeMarcadores()).isPositive();
    for (String trecho : trechosEsperados) {
      assertThat(capturados)
          .as("o log do fluxo deveria ter passado pela captura: %s", trecho)
          .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains(trecho));
    }
    detector.exigirNenhumVazamento(capturados);
  }

  // ---------- testes ----------

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOAvisoSaiPorSms() {
    UUID a1 = agendamento(paciente(true, true), 0);
    UUID a2 = agendamento(paciente(true, false), 1);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(statusDoAviso(eventoId, a2)).isEqualTo("CONFIRMADO");
    semDadoDePacienteNoLog("Lote do evento " + eventoId + " concluído");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOSmsFalhaECaiNoEmail() {
    SMS.stubFor(post(urlPathEqualTo("/v1/mensagens")).willReturn(aResponse().withStatus(500)));
    UUID a1 = agendamento(paciente(true, true), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(EMAIL.getAllServeEvents()).hasSize(1);
    semDadoDePacienteNoLog(
        "Canal sms tentativa 1 falhou e sera repetida: causa=HTTP 500",
        "Canal sms esgotado",
        "Lote do evento " + eventoId + " concluído");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoSmsEEmailFalham() {
    SMS.stubFor(post(urlPathEqualTo("/v1/mensagens")).willReturn(aResponse().withStatus(500)));
    EMAIL.stubFor(post(urlPathEqualTo("/v1/emails")).willReturn(aResponse().withStatus(503)));
    UUID a1 = agendamento(paciente(true, true), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("CANAIS_ESGOTADOS");
    semDadoDePacienteNoLog(
        "Canal sms tentativa 1 falhou e sera repetida: causa=HTTP 500",
        "Canal email tentativa 1 falhou e sera repetida: causa=HTTP 503",
        "Canal sms esgotado",
        "Canal email esgotado",
        "Lote do evento " + eventoId + " concluído");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoARespostaEstaForaDoContrato() {
    SMS.stubFor(
        post(urlPathEqualTo("/v1/mensagens"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{corpo ilegivel")));
    UUID a1 = agendamento(paciente(true, true), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("PENDENTE");
    assertThat(EMAIL.getAllServeEvents()).isEmpty();
    semDadoDePacienteNoLog("Canal sms indeterminado", "avisoIndeterminado: eventoId=" + eventoId);
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoAConexaoDoSmsEDerrubada() {
    SMS.stubFor(
        post(urlPathEqualTo("/v1/mensagens"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    UUID a1 = agendamento(paciente(true, true), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CONFIRMADO");
    assertThat(EMAIL.getAllServeEvents()).hasSize(1);
    // A linha de comunicação e a do retry passam pela captura; a mensagem do JDK (que pode citar o
    // endereço) não.
    semDadoDePacienteNoLog(
        "Canal sms tentativa 1 falhou e sera repetida: causa=java.",
        "Canal sms esgotado: falha de comunicação (",
        "Lote do evento " + eventoId + " concluído");
  }

  /**
   * Contato em branco só existe se o CHECK do banco for contornado (dado antigo, carga externa): o
   * teste derruba os dois CHECKs, grava o paciente e os recria no fim, como o teste do adapter.
   */
  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOContatoEstaEmBranco() {
    UUID paciente = UUID.randomUUID();
    try {
      jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT ck_paciente_telefone");
      jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT ck_paciente_email");
      String nome = "Marcadora Quixabeira Branca " + (++sequencia);
      String email = "marcador.branco." + sequencia + "@exemplo.invalid";
      LocalDate nascimento = LocalDate.of(1944, 8, 19);
      jdbc.update(
          "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, E'\\t',"
              + " ?, ?)",
          paciente,
          nome,
          email,
          java.sql.Date.valueOf(nascimento));
      pacientes.add(paciente);
      detector.paciente(nome, null, email, nascimento);
      UUID a1 = agendamento(paciente, 0);

      UUID eventoId = indisponivel();
      despachante.despachar();

      assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("SEM_CONTATO");
      assertThat(SMS.getAllServeEvents()).isEmpty();
      assertThat(EMAIL.getAllServeEvents()).isEmpty();
      semDadoDePacienteNoLog(
          "Paciente " + paciente + " com contato em branco",
          "Lote do evento " + eventoId + " concluído");
    } finally {
      // Cada passo é independente: a falha de um não impede os outros.
      try {
        jdbc.update("DELETE FROM agendamento_exame WHERE paciente_id = ?", paciente);
        jdbc.update("DELETE FROM paciente WHERE id = ?", paciente);
      } finally {
        try {
          recriarCheckDeBranco("ck_paciente_telefone", "telefone");
        } finally {
          recriarCheckDeBranco("ck_paciente_email", "email");
        }
      }
    }
  }

  /** Idempotente: existe ou não, o CHECK sai daqui igual ao da migration V2. */
  private void recriarCheckDeBranco(String nome, String coluna) {
    jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT IF EXISTS " + nome);
    jdbc.execute(
        "ALTER TABLE paciente ADD CONSTRAINT "
            + nome
            + " CHECK ("
            + coluna
            + " IS NULL OR "
            + coluna
            + " !~ '^\\s*$')");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOPacienteNaoTemContato() {
    UUID a1 = agendamento(paciente(false, false), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("SEM_CONTATO");
    semDadoDePacienteNoLog("Lote do evento " + eventoId + " concluído");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOEquipamentoVoltaAntesDoEnvio() {
    UUID a1 = agendamento(paciente(true, true), 0);

    UUID eventoId = indisponivel();
    marcarDisponivel.executar(upa, tomografo);
    despachante.despachar();

    assertThat(statusDoAviso(eventoId, a1)).isEqualTo("CANCELADO");
    assertThat(SMS.getAllServeEvents()).isEmpty();
    semDadoDePacienteNoLog("Lote do evento " + eventoId + " concluído");
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOAgendamentoEOrfao() {
    // Paciente inexistente: o exame continua sendo dado sensível e entra no detector.
    UUID a1 = agendamento(UUID.randomUUID(), 0);

    UUID eventoId = indisponivel();
    despachante.despachar();

    assertThat(motivosDeRevisao(eventoId, a1)).containsExactly("DADO_INCONSISTENTE");
    semDadoDePacienteNoLog(
        "Agendamento " + a1 + " com dado inconsistente", "dadoInconsistente: eventoId=" + eventoId);
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteEmLogQuandoOPayloadDoOutboxEIlegivel() {
    // Payload que não vira evento e carrega dado de paciente: nem o alerta nem o Jackson o logam.
    paciente(true, true);
    String nome = "Marcadora Quixabeira Nomeada " + sequencia;
    String telefone = "+55219876" + String.format("%05d", 40_000 + sequencia);
    UUID eventoId = UUID.randomUUID();
    eventos.add(eventoId);
    jdbc.update(
        "INSERT INTO outbox_evento (id, tipo, payload, ocorrido_em) VALUES (?,"
            + " 'EquipamentoIndisponivelEvent', CAST(? AS jsonb), ?)",
        eventoId,
        "{\"eventoId\":\""
            + eventoId
            + "\",\"idEquipamento\":null,\"nome\":\""
            + nome
            + "\",\"telefone\":\""
            + telefone
            + "\"}",
        Timestamp.from(AGORA));

    despachante.despachar();

    assertThat(situacaoDoEvento(eventoId)).isEqualTo("ESGOTADO");
    semDadoDePacienteNoLog("eventoEsgotado: eventoId=" + eventoId);
  }
}
