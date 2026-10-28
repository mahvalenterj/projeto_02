package br.gov.saude.upa.infrastructure.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import br.gov.saude.upa.ChavesDeTeste;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * PATCH de verdade (cadeia de filtros do Spring Security, JwtDecoder com a chave do teste,
 * controller, caso de uso, Postgres, outbox) e, com {@code despachar()}, os avisos no WireMock. O
 * MockMvc é montado sobre o contexto compartilhado de {@link CanaisSimuladosIntegracaoTest}, sem
 * contexto novo. Tokens emitidos no {@code AGORA} do relógio fixo. Dados sintéticos.
 */
class StatusDoEquipamentoHttpIntegracaoTest extends CanaisSimuladosIntegracaoTest {

  private static final Instant EXAME = Instant.parse("2026-10-11T19:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private WebApplicationContext contexto;
  @Autowired private DespachanteDoOutbox despachante;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private MockMvc mvc;
  private UUID upa;
  private UUID outraUpa;
  private UUID tomografo;
  private UUID tomografoDaOutra;
  private final List<UUID> pacientes = new ArrayList<>();

  @BeforeEach
  void prepararCenario() {
    mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    SMS.resetAll();
    EMAIL.resetAll();
    SMS.stubFor(
        post(urlPathEqualTo("/v1/mensagens"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"msg-1\",\"situacao\":\"ACEITA\"}")));
    upa = UUID.randomUUID();
    outraUpa = UUID.randomUUID();
    tomografo = UUID.randomUUID();
    tomografoDaOutra = UUID.randomUUID();
    for (UUID[] par : new UUID[][] {{upa, tomografo}, {outraUpa, tomografoDaOutra}}) {
      jdbc.update("INSERT INTO upa (id, nome) VALUES (?, ?)", par[0], "UPA Sintetica");
      jdbc.update(
          "INSERT INTO equipamento (id, upa_id, tipo, status) VALUES (?, ?, 'TOMOGRAFO',"
              + " 'DISPONIVEL')",
          par[1],
          par[0]);
      jdbc.update(
          "INSERT INTO upa_endereco_alternativo (upa_id, tipo, texto) VALUES (?, 'TOMOGRAFO', ?)",
          par[0],
          "UPA Vizinha Sintetica, Rua Ficticia 1");
    }
  }

  @AfterEach
  void limparOQueOTesteCriou() {
    for (UUID equipamento : List.of(tomografo, tomografoDaOutra)) {
      List<UUID> eventos =
          jdbc.queryForList(
              "SELECT id FROM outbox_evento WHERE CAST(payload AS text) LIKE ?",
              UUID.class,
              "%" + equipamento + "%");
      for (UUID evento : eventos) {
        jdbc.update("DELETE FROM aviso_paciente WHERE evento_id = ?", evento);
        jdbc.update("DELETE FROM revisao_manual WHERE evento_id = ?", evento);
        jdbc.update("DELETE FROM outbox_evento WHERE id = ?", evento);
      }
    }
    for (UUID u : List.of(upa, outraUpa)) {
      jdbc.update("DELETE FROM agendamento_exame WHERE upa_id = ?", u);
      jdbc.update("DELETE FROM upa_endereco_alternativo WHERE upa_id = ?", u);
      jdbc.update("DELETE FROM equipamento WHERE upa_id = ?", u);
      jdbc.update("DELETE FROM upa WHERE id = ?", u);
    }
    for (UUID paciente : pacientes) {
      jdbc.update("DELETE FROM paciente WHERE id = ?", paciente);
    }
    pacientes.clear();
  }

  private UUID agendamentoComTelefone(String telefone) {
    UUID paciente = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, NULL,"
            + " ?)",
        paciente,
        "Paciente Sintetico Http",
        telefone,
        java.sql.Date.valueOf(LocalDate.of(1980, 1, 1)));
    pacientes.add(paciente);
    UUID agendamento = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id, criado_em,"
            + " inicio_exame) VALUES (?, 'TOMOGRAFIA', ?, ?, ?, ?, ?)",
        agendamento,
        paciente,
        upa,
        tomografo,
        Timestamp.from(AGORA.minusSeconds(86_400)),
        Timestamp.from(EXAME));
    return agendamento;
  }

  private MockHttpServletResponse patchStatus(
      UUID idUpa, UUID equipamento, String status, String token) throws Exception {
    return mvc.perform(
            patch("/api/v1/upas/" + idUpa + "/equipamentos/" + equipamento + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"))
        .andReturn()
        .getResponse();
  }

  private String statusNoBanco(UUID equipamento) {
    return jdbc.queryForObject(
        "SELECT status FROM equipamento WHERE id = ?", String.class, equipamento);
  }

  private List<String> eventosPendentesDe(UUID equipamento) {
    return jdbc.queryForList(
        "SELECT situacao FROM outbox_evento WHERE CAST(payload AS text) LIKE ?",
        String.class,
        "%" + equipamento + "%");
  }

  private static Map<String, Object> corpoSemInstance(MockHttpServletResponse resposta)
      throws Exception {
    Map<String, Object> corpo =
        new LinkedHashMap<>(
            JSON.readValue(
                resposta.getContentAsString(), new TypeReference<Map<String, Object>>() {}));
    corpo.remove("instance");
    return corpo;
  }

  @Test
  void ca13_deveGravarOOutboxEAvisarOsPacientesQuandoOPatchMarcaIndisponivel() throws Exception {
    UUID agendamento = agendamentoComTelefone("+5521900000801");
    String token = ChavesDeTeste.token(AGORA, upa);

    MockHttpServletResponse resposta = patchStatus(upa, tomografo, "INDISPONIVEL", token);

    assertThat(resposta.getStatus()).isEqualTo(204);
    assertThat(statusNoBanco(tomografo)).isEqualTo("INDISPONIVEL");
    assertThat(eventosPendentesDe(tomografo)).containsExactly("PENDENTE");

    despachante.despachar();

    assertThat(eventosPendentesDe(tomografo)).containsExactly("PROCESSADO");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM aviso_paciente WHERE agendamento_id = ?",
                String.class,
                agendamento))
        .isEqualTo("CONFIRMADO");
    assertThat(SMS.getAllServeEvents()).hasSize(1);

    MockHttpServletResponse volta = patchStatus(upa, tomografo, "DISPONIVEL", token);
    assertThat(volta.getStatus()).isEqualTo(204);
    assertThat(statusNoBanco(tomografo)).isEqualTo("DISPONIVEL");
    assertThat(eventosPendentesDe(tomografo)).containsExactly("PROCESSADO");
  }

  @Test
  void ca13_deveResponder404IdenticoComBancoRealSemMudarNada() throws Exception {
    // Operador só da UPA "upa": a outra UPA existe, mas para ele é igual a uma inexistente.
    String token =
        ChavesDeTeste.token(AGORA, upa, UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    MockHttpServletResponse outraUpaExistente =
        patchStatus(outraUpa, tomografoDaOutra, "INDISPONIVEL", token);
    MockHttpServletResponse upaInexistenteNoToken =
        patchStatus(
            UUID.fromString("00000000-0000-0000-0000-00000000000a"),
            tomografo,
            "INDISPONIVEL",
            token);
    MockHttpServletResponse equipamentoDeOutraUpa =
        patchStatus(upa, tomografoDaOutra, "INDISPONIVEL", token);
    MockHttpServletResponse equipamentoInexistente =
        patchStatus(upa, UUID.randomUUID(), "DISPONIVEL", token);

    for (MockHttpServletResponse resposta :
        List.of(upaInexistenteNoToken, equipamentoDeOutraUpa, equipamentoInexistente)) {
      assertThat(resposta.getStatus()).isEqualTo(404);
      assertThat(resposta.getContentType()).isEqualTo("application/problem+json");
      assertThat(corpoSemInstance(resposta)).isEqualTo(corpoSemInstance(outraUpaExistente));
    }
    assertThat(outraUpaExistente.getStatus()).isEqualTo(404);
    assertThat(statusNoBanco(tomografoDaOutra)).isEqualTo("DISPONIVEL");
    assertThat(statusNoBanco(tomografo)).isEqualTo("DISPONIVEL");
    assertThat(eventosPendentesDe(tomografoDaOutra)).isEmpty();
    assertThat(eventosPendentesDe(tomografo)).isEmpty();
  }

  @Test
  void ca13_deveResponder422QuandoOEquipamentoJaEstaIndisponivel() throws Exception {
    String token = ChavesDeTeste.token(AGORA, upa);
    assertThat(patchStatus(upa, tomografo, "INDISPONIVEL", token).getStatus()).isEqualTo(204);

    MockHttpServletResponse repetido = patchStatus(upa, tomografo, "INDISPONIVEL", token);
    MockHttpServletResponse manutencao = patchStatus(upa, tomografo, "EM_MANUTENCAO", token);

    assertThat(repetido.getStatus()).isEqualTo(422);
    assertThat(manutencao.getStatus()).isEqualTo(422);
    assertThat(eventosPendentesDe(tomografo)).hasSize(1);
    assertThat(statusNoBanco(tomografo)).isEqualTo("INDISPONIVEL");
  }

  /**
   * Conflito de versão determinístico: uma transação do teste segura a linha do equipamento e
   * incrementa a versão; o PATCH lê a versão antiga (MVCC), fica bloqueado no UPDATE e, quando o
   * teste confirma, o {@code WHERE version = ?} não acha a linha: lock otimista, 409.
   */
  @Test
  void ca13_deveResponder409QuandoOutraOperacaoGravaOEquipamentoAntes() throws Exception {
    String token = ChavesDeTeste.token(AGORA, upa);
    MockHttpServletResponse conflito;
    try (Connection concorrente = dataSource.getConnection()) {
      concorrente.setAutoCommit(false);
      try (PreparedStatement update =
          concorrente.prepareStatement(
              "UPDATE equipamento SET version = version + 1 WHERE id = ?")) {
        update.setObject(1, tomografo);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      CompletableFuture<MockHttpServletResponse> patch =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return patchStatus(upa, tomografo, "INDISPONIVEL", token);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      esperarOPatchBloquearNaLinha();
      concorrente.commit();
      conflito = patch.get(30, TimeUnit.SECONDS);
    }

    assertThat(conflito.getStatus()).isEqualTo(409);
    assertThat(conflito.getContentType()).isEqualTo("application/problem+json");
    assertThat(conflito.getContentAsString()).doesNotContain("version").doesNotContain("SQL");
    assertThat(statusNoBanco(tomografo)).isEqualTo("DISPONIVEL");
    assertThat(eventosPendentesDe(tomografo)).isEmpty();
  }

  private void esperarOPatchBloquearNaLinha() throws InterruptedException {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < limite) {
      Integer bloqueados =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                  + " AND query ILIKE 'update equipamento%'",
              Integer.class);
      if (bloqueados != null && bloqueados > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("o PATCH não chegou a bloquear na linha do equipamento");
  }
}
