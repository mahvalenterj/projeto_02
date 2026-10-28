package br.gov.saude.upa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import br.gov.saude.upa.ChavesDeTeste;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.infrastructure.config.SegurancaConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base dos testes da camada web: um único contexto {@code @WebMvcTest} com a configuração de
 * segurança REAL (JwtDecoder com a chave pública do par gerado no teste, validadores, entry point)
 * e os casos de uso simulados. Relógio fixo, para os tokens assinados no teste terem validade
 * determinística.
 */
@WebMvcTest(EquipamentoStatusController.class)
@Import({
  SegurancaConfiguration.class,
  AutorizacaoPorUpa.class,
  RespostaDeSegurancaEmProblemDetail.class,
  ErroGenericoController.class,
  WebComSegurancaTest.RelogioFixo.class
})
@TestPropertySource(
    properties = {
      "upa.seguranca.jwt.emissor=" + ChavesDeTeste.EMISSOR,
      "upa.seguranca.jwt.audiencia=" + ChavesDeTeste.AUDIENCIA
    })
abstract class WebComSegurancaTest {

  static final Instant AGORA = Instant.parse("2026-10-27T13:00:00Z");
  static final ObjectMapper JSON = new ObjectMapper();

  @DynamicPropertySource
  static void chavePublica(DynamicPropertyRegistry registro) {
    registro.add("upa.seguranca.jwt.chave-publica", ChavesDeTeste::pemPublica);
  }

  @Autowired MockMvc mvc;
  @MockitoBean MarcarEquipamentoIndisponivel marcarIndisponivel;
  @MockitoBean MarcarEquipamentoDisponivel marcarDisponivel;

  @TestConfiguration(proxyBeanMethods = false)
  static class RelogioFixo {
    @Bean
    Clock relogioDeTeste() {
      return Clock.fixed(AGORA, ZoneOffset.UTC);
    }
  }

  static String caminho(UUID upa, UUID equipamento) {
    return "/api/v1/upas/" + upa + "/equipamentos/" + equipamento + "/status";
  }

  static MockHttpServletRequestBuilder patchStatus(UUID upa, UUID equipamento, String status) {
    return patch(caminho(upa, equipamento))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"" + status + "\"}");
  }

  static Map<String, Object> corpo(MockHttpServletResponse resposta) throws Exception {
    return JSON.readValue(
        resposta.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
        new TypeReference<>() {});
  }

  /** Corpo sem {@code instance}, o único campo que pode variar entre respostas idênticas. */
  static Map<String, Object> corpoSemInstance(MockHttpServletResponse resposta) throws Exception {
    Map<String, Object> corpo = new LinkedHashMap<>(corpo(resposta));
    corpo.remove("instance");
    return corpo;
  }

  static Map<String, String> cabecalhos(MockHttpServletResponse resposta) {
    Map<String, String> todos = new TreeMap<>();
    for (String nome : resposta.getHeaderNames()) {
      todos.put(nome, String.join(",", resposta.getHeaders(nome)));
    }
    return todos;
  }

  /** ProblemDetail (RFC 9457) sem stack trace, nome de exceção, SQL nem mensagem interna. */
  static void exigirProblemDetailLimpo(MockHttpServletResponse resposta, int status)
      throws Exception {
    assertThat(resposta.getStatus()).isEqualTo(status);
    assertThat(resposta.getContentType()).isEqualTo("application/problem+json");
    Map<String, Object> corpo = corpo(resposta);
    assertThat(corpo).containsEntry("status", status);
    assertThat(corpo).containsKeys("type", "title", "detail");
    assertThat(corpo.keySet()).isSubsetOf("type", "title", "status", "detail", "instance");
    String texto = resposta.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(texto)
        .doesNotContain("Exception")
        .doesNotContain("at br.")
        .doesNotContain("at org.")
        .doesNotContainIgnoringCase("select ")
        .doesNotContainIgnoringCase("sql")
        .doesNotContain("trace");
  }
}
