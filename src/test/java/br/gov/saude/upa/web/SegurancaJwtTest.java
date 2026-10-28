package br.gov.saude.upa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import br.gov.saude.upa.ChavesDeTeste;
import br.gov.saude.upa.DetectorDeDadoSensivel;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nimbusds.jose.JWSAlgorithm;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Validação do JWT RS256 de verdade (ADR 0004): o {@code JwtDecoder} da aplicação, com a chave
 * pública do par gerado no teste, recusa assinatura de outra chave, algoritmo diferente de RS256,
 * {@code iss}/{@code aud} errados, token vencido além da tolerância de 30 s, duração acima de 15
 * min e claims obrigatórias ausentes. Toda recusa é o MESMO 401 em ProblemDetail, sem dizer o
 * motivo, e o token nunca vai para o log.
 */
class SegurancaJwtTest extends WebComSegurancaTest {

  private final UUID upa = UUID.randomUUID();
  private final UUID equipamento = UUID.randomUUID();
  private final DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();
  private final List<String> tokensUsados = new ArrayList<>();

  @BeforeEach
  void ligarCaptura() {
    captura.ligarNaRaiz();
  }

  @AfterEach
  void desligarCaptura() {
    captura.desligar();
    captura.limpar();
  }

  private MockHttpServletResponse comToken(String token) throws Exception {
    tokensUsados.add(token);
    return mvc.perform(
            patchStatus(upa, equipamento, "DISPONIVEL").header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse();
  }

  private ChavesDeTeste.Construtor valido() {
    return ChavesDeTeste.novo(AGORA).upas(upa);
  }

  @Test
  void deveAceitarTokenRs256ValidoAssinadoPelaChaveConfigurada() throws Exception {
    MockHttpServletResponse resposta = comToken(valido().assinar());

    assertThat(resposta.getStatus()).isEqualTo(204);
    verify(marcarDisponivel).executar(upa, equipamento);
  }

  @Test
  void deveAceitarTokenVencidoDentroDaToleranciaDe30Segundos() throws Exception {
    Instant exp = AGORA.minusSeconds(20);
    MockHttpServletResponse resposta =
        comToken(valido().emitidoEm(exp.minus(Duration.ofMinutes(15))).expiraEm(exp).assinar());

    assertThat(resposta.getStatus()).isEqualTo(204);
  }

  @Test
  void deveAceitarTokenComDuracaoDeExatos15Minutos() throws Exception {
    MockHttpServletResponse resposta =
        comToken(valido().emitidoEm(AGORA).expiraEm(AGORA.plus(Duration.ofMinutes(15))).assinar());

    assertThat(resposta.getStatus()).isEqualTo(204);
  }

  @Test
  void deveResponderOMesmo401SemMotivoParaQualquerTokenInvalido() throws Exception {
    Map<String, String> invalidos = new LinkedHashMap<>();
    invalidos.put(
        "assinatura de outra chave",
        valido().assinadoCom(ChavesDeTeste.OUTRO.getPrivate()).assinar());
    invalidos.put("RS512 em vez de RS256", valido().algoritmo(JWSAlgorithm.RS512).assinar());
    invalidos.put("iss errado", valido().emissor("https://outro.invalid").assinar());
    invalidos.put("aud errada", valido().audiencia("outra-api").assinar());
    Instant vencido = AGORA.minusSeconds(31);
    invalidos.put(
        "vencido além da tolerância",
        valido().emitidoEm(vencido.minus(Duration.ofMinutes(10))).expiraEm(vencido).assinar());
    invalidos.put(
        "duração acima de 15 min",
        valido()
            .emitidoEm(AGORA)
            .expiraEm(AGORA.plus(Duration.ofMinutes(15)).plusSeconds(1))
            .assinar());
    invalidos.put("sem iat", valido().emitidoEm(null).assinar());
    invalidos.put("sem exp", valido().expiraEm(null).assinar());
    invalidos.put("sem sub", valido().sujeito(null).assinar());
    invalidos.put("sem iss", valido().emissor(null).assinar());
    invalidos.put(
        "emitido no futuro",
        valido()
            .emitidoEm(AGORA.plus(Duration.ofMinutes(5)))
            .expiraEm(AGORA.plus(Duration.ofMinutes(10)))
            .assinar());
    invalidos.put("alg none", semAssinatura(valido().assinar()));
    invalidos.put("malformado", "nao.e.um-jwt");

    MockHttpServletResponse referencia = null;
    for (Map.Entry<String, String> caso : invalidos.entrySet()) {
      MockHttpServletResponse resposta = comToken(caso.getValue());
      assertThat(resposta.getStatus()).as(caso.getKey()).isEqualTo(401);
      exigirProblemDetailLimpo(resposta, 401);
      assertThat(resposta.getHeader("WWW-Authenticate"))
          .as(caso.getKey())
          .isEqualTo("Bearer realm=\"upa\"");
      if (referencia == null) {
        referencia = resposta;
      }
      assertThat(corpoSemInstance(resposta))
          .as(caso.getKey())
          .isEqualTo(corpoSemInstance(referencia));
      assertThat(cabecalhos(resposta)).as(caso.getKey()).isEqualTo(cabecalhos(referencia));
    }
    assertThat(corpo(referencia)).containsEntry("title", "Não autenticado");
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);
    semTokenNoLog();
  }

  @Test
  void deveResponder401IgualAoDeTokenInvalidoQuandoNaoHaTokenOuOEsquemaNaoEBearer()
      throws Exception {
    MockHttpServletResponse invalido = comToken("nao.e.um-jwt");
    MockHttpServletResponse semToken =
        mvc.perform(patchStatus(upa, equipamento, "DISPONIVEL")).andReturn().getResponse();
    MockHttpServletResponse basic =
        mvc.perform(
                patchStatus(upa, equipamento, "DISPONIVEL")
                    .header("Authorization", "Basic dXNlcjpzZW5oYQ=="))
            .andReturn()
            .getResponse();

    for (MockHttpServletResponse resposta : List.of(semToken, basic)) {
      exigirProblemDetailLimpo(resposta, 401);
      assertThat(corpoSemInstance(resposta)).isEqualTo(corpoSemInstance(invalido));
      assertThat(cabecalhos(resposta)).isEqualTo(cabecalhos(invalido));
    }
    semTokenNoLog();
  }

  @Test
  void naoDeveRegistrarOTokenNoLogNemQuandoEleEAceito() throws Exception {
    MockHttpServletResponse resposta = comToken(valido().assinar());

    assertThat(resposta.getStatus()).isEqualTo(204);
    assertThat(captura.eventos())
        .as("o log de auditoria do PATCH deveria ter passado pela captura")
        .anySatisfy(
            e -> assertThat(e.getFormattedMessage()).contains("equipamentoId=" + equipamento));
    semTokenNoLog();
  }

  private static String semAssinatura(String token) {
    String[] partes = token.split("\\.");
    String cabecalho =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return cabecalho + "." + partes[1] + ".";
  }

  /** Nem o token inteiro nem a assinatura (a parte que o torna utilizável) aparecem no log. */
  private void semTokenNoLog() {
    List<ILoggingEvent> eventos = captura.eventos();
    for (String token : tokensUsados) {
      String[] partes = token.split("\\.");
      String assinatura = partes.length == 3 ? partes[2] : token;
      for (ILoggingEvent evento : eventos) {
        String texto =
            evento.getFormattedMessage()
                + " "
                + (evento.getThrowableProxy() == null
                    ? ""
                    : evento.getThrowableProxy().getMessage());
        assertThat(texto).doesNotContain(token);
        if (!assinatura.isEmpty()) {
          assertThat(texto).doesNotContain(assinatura);
        }
      }
    }
  }
}
