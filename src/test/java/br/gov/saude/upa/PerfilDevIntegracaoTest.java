package br.gov.saude.upa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.gov.saude.upa.infrastructure.config.AgendadorDoOutbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * O kit de desenvolvimento sobe de verdade no perfil {@code dev}: cenário sintético, token assinado
 * com o par de dev ({@code config/dev/jwt}), PATCH aceito com esse token, Swagger ligado e o
 * agendador do outbox ativo. Junto com os testes de unidade da trava e do emissor de dev, é o que
 * usa a chave de dev, e só para provar que o kit funciona; a segurança da API é provada com o par
 * gerado no teste. Postgres do Testcontainers no lugar do compose; os canais apontam para uma porta
 * sem servidor. O agendador existe (bean), mas com intervalo de 1 h e retry curto: este contexto
 * fica vivo no cache e não pode despachar no meio de outros testes que capturam o logger raiz.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
@TestPropertySource(
    properties = {
      "upa.canais.sms.base-url=http://localhost:1",
      "upa.canais.email.base-url=http://localhost:1",
      "upa.outbox.intervalo=PT1H",
      "upa.canais.sms.retry.tentativas=1",
      "upa.canais.email.retry.tentativas=1",
      "upa.canais.sms.retry.espera-inicial=PT0.01S",
      "upa.canais.email.retry.espera-inicial=PT0.01S",
      "upa.canais.sms.timeout-conexao=PT0.2S",
      "upa.canais.email.timeout-conexao=PT0.2S"
    })
class PerfilDevIntegracaoTest {

  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private WebApplicationContext contexto;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void montarMockMvc() {
    mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
  }

  private JsonNode postJson(String caminho, String corpo) throws Exception {
    MockHttpServletResponse resposta =
        mvc.perform(post(caminho).contentType(MediaType.APPLICATION_JSON).content(corpo))
            .andReturn()
            .getResponse();
    assertThat(resposta.getStatus()).as(caminho).isEqualTo(200);
    return JSON.readTree(resposta.getContentAsString());
  }

  @Test
  void deveCriarCenarioEmitirTokenEAceitarOPatchNoPerfilDev() throws Exception {
    Instant antes = Instant.now();
    JsonNode cenario = postJson("/dev/cenario", "");
    UUID upa = UUID.fromString(cenario.get("upaId").asText());
    UUID equipamento = UUID.fromString(cenario.get("equipamentoId").asText());

    assertThat(cenario.get("agendamentos").size()).isBetween(3, 5);
    for (JsonNode agendamento : cenario.get("agendamentos")) {
      Instant inicio = Instant.parse(agendamento.get("inicioExame").asText());
      assertThat(inicio).isAfter(antes);
      assertThat(inicio.atZone(SAO_PAULO).toLocalDate()).isEqualTo(LocalDate.now(SAO_PAULO));
      assertThat(agendamento.get("canal").asText()).isIn("SMS", "EMAIL");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM equipamento WHERE id = ?", String.class, equipamento))
        .isEqualTo("DISPONIVEL");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM paciente p JOIN agendamento_exame a ON a.paciente_id = p.id"
                    + " WHERE a.upa_id = ? AND (p.telefone LIKE '+55219999%' OR p.email LIKE"
                    + " '%@exemplo.invalid')",
                Integer.class, upa))
        .as("contatos sintéticos")
        .isEqualTo(cenario.get("agendamentos").size());

    String token = postJson("/dev/token", "{\"upas\":[\"" + upa + "\"]}").get("token").asText();
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("upas")).containsExactly(upa.toString());
    assertThat(
            Duration.between(
                jwt.getJWTClaimsSet().getIssueTime().toInstant(),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant()))
        .isEqualTo(Duration.ofMinutes(15));

    MockHttpServletResponse resposta =
        mvc.perform(
                patch("/api/v1/upas/" + upa + "/equipamentos/" + equipamento + "/status")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"INDISPONIVEL\"}"))
            .andReturn()
            .getResponse();
    assertThat(resposta.getStatus()).isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_evento WHERE CAST(payload AS text) LIKE ?",
                Integer.class,
                "%" + equipamento + "%"))
        .isEqualTo(1);
  }

  @Test
  void deveRecusarPedidoDeTokenSemUpaValida() throws Exception {
    MockHttpServletResponse invalido =
        mvc.perform(
                post("/dev/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"upas\":[\"nao-e-uuid\"]}"))
            .andReturn()
            .getResponse();
    MockHttpServletResponse vazio =
        mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn()
            .getResponse();

    String vinteEUma =
        java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
            .collect(java.util.stream.Collectors.joining(",", "{\"upas\":[", "]}"));
    MockHttpServletResponse demais =
        mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(vinteEUma))
            .andReturn()
            .getResponse();

    assertThat(invalido.getStatus()).isEqualTo(400);
    assertThat(vazio.getStatus()).isEqualTo(400);
    assertThat(demais.getStatus()).isEqualTo(400);
    assertThat(demais.getContentAsString()).doesNotContain("\"token\":");
  }

  @Test
  void deveResponder404NosEndpointsDeDevParaRequisicaoQueNaoVemDoLoopback() throws Exception {
    MockHttpServletResponse deFora =
        mvc.perform(
                post("/dev/token")
                    .with(
                        r -> {
                          r.setRemoteAddr("192.168.0.10");
                          return r;
                        })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"upas\":[\"" + UUID.randomUUID() + "\"]}"))
            .andReturn()
            .getResponse();
    MockHttpServletResponse cenarioDeFora =
        mvc.perform(
                post("/dev/cenario")
                    .with(
                        r -> {
                          r.setRemoteAddr("10.0.0.7");
                          return r;
                        }))
            .andReturn()
            .getResponse();
    MockHttpServletResponse ipv6Local =
        mvc.perform(
                post("/dev/token")
                    .with(
                        r -> {
                          r.setRemoteAddr("0:0:0:0:0:0:0:1");
                          return r;
                        })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"upas\":[\"" + UUID.randomUUID() + "\"]}"))
            .andReturn()
            .getResponse();

    assertThat(deFora.getStatus()).isEqualTo(404);
    assertThat(deFora.getContentAsString()).doesNotContain("\"token\":");
    assertThat(cenarioDeFora.getStatus()).isEqualTo(404);
    assertThat(ipv6Local.getStatus()).isEqualTo(200);
  }

  @Test
  void deveLigarSwaggerEAgendadorDoOutboxNoPerfilDev() throws Exception {
    MockHttpServletResponse apiDocs = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
    MockHttpServletResponse swagger =
        mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse();

    assertThat(apiDocs.getStatus()).isEqualTo(200);
    assertThat(apiDocs.getContentAsString())
        .contains("/api/v1/upas/{upaId}/equipamentos/{id}/status")
        .contains("\"bearer-jwt\"")
        .contains("\"scheme\":\"bearer\"")
        .contains("\"bearerFormat\":\"JWT\"");
    assertThat(swagger.getStatus()).isEqualTo(200);
    assertThat(contexto.getBeanNamesForType(AgendadorDoOutbox.class)).hasSize(1);
  }
}
