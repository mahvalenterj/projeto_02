package br.gov.saude.upa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import jakarta.servlet.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Erros fora do caminho feliz do contrato: 405/415 com detalhe FIXO (sem ecoar o que o cliente
 * mandou), requisição rejeitada pelo firewall do Spring Security e o {@code /error} do container,
 * todos em ProblemDetail genérico, nunca no JSON padrão do {@code BasicErrorController}.
 */
class ErrosForaDoContratoTest extends WebComSegurancaTest {

  private final UUID upa = UUID.randomUUID();
  private final UUID equipamento = UUID.randomUUID();

  private MockHttpServletResponse executar(MockHttpServletRequestBuilder requisicao)
      throws Exception {
    return mvc.perform(requisicao).andReturn().getResponse();
  }

  @Test
  void deveResponder405E415ComDetalheFixoSemEcoarARequisicao() throws Exception {
    MockHttpServletResponse metodo =
        executar(
            get(caminho(upa, equipamento))
                .with(jwt().jwt(j -> j.claim("upas", List.of(upa.toString())))));
    MockHttpServletResponse tipo =
        executar(
            patch(caminho(upa, equipamento))
                .contentType(MediaType.parseMediaType("application/x-marcador-ecoado"))
                .content("x")
                .with(jwt().jwt(j -> j.claim("upas", List.of(upa.toString())))));

    exigirProblemDetailLimpo(metodo, 405);
    exigirProblemDetailLimpo(tipo, 415);
    assertThat(corpo(metodo))
        .containsEntry("title", "Método não permitido")
        .containsEntry("detail", "O método HTTP usado não é aceito neste recurso.");
    assertThat(corpo(tipo))
        .containsEntry("title", "Tipo de conteúdo não suportado")
        .containsEntry("detail", "O tipo de conteúdo enviado não é aceito neste recurso.");
    assertThat(metodo.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("GET");
    assertThat(tipo.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("marcador-ecoado");
    assertThat(metodo.getHeader("Allow")).contains("PATCH");
  }

  @Test
  void deveResponder400EmProblemDetailQuandoOFirewallRejeitaAUrl() throws Exception {
    // A URI crua é fixada no request: o MockMvc normaliza parte do caminho antes do filtro.
    MockHttpServletResponse pontoEVirgula =
        executar(
            get("/api/v1/upas")
                .with(
                    r -> {
                      r.setRequestURI("/api/v1/upas;x=marcador/equipamentos");
                      return r;
                    }));
    MockHttpServletResponse barraDupla =
        executar(
            get("/api/v1/upas")
                .with(
                    r -> {
                      r.setRequestURI("/api/v1//upas/marcador");
                      return r;
                    }));

    for (MockHttpServletResponse resposta : List.of(pontoEVirgula, barraDupla)) {
      exigirProblemDetailLimpo(resposta, 400);
      assertThat(corpo(resposta))
          .containsEntry("title", "Requisição inválida")
          .doesNotContainKeys("timestamp", "error", "path");
      assertThat(resposta.getContentAsString(StandardCharsets.UTF_8))
          .doesNotContain("marcador")
          .doesNotContain("rejected");
    }
  }

  @Test
  void deveResponderOErrorDoContainerEmProblemDetailGenerico() throws Exception {
    MockHttpServletResponse erro500 =
        executar(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/qualquer")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException("SELECT segredo FROM paciente")));
    MockHttpServletResponse erro400 =
        executar(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400));
    MockHttpServletResponse semStatus = executar(get("/error"));

    exigirProblemDetailLimpo(erro500, 500);
    exigirProblemDetailLimpo(erro400, 400);
    exigirProblemDetailLimpo(semStatus, 500);
    assertThat(corpo(erro500))
        .containsEntry("title", "Erro interno")
        .containsEntry("instance", "/api/v1/qualquer")
        .doesNotContainKeys("timestamp", "error", "path");
    assertThat(erro500.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("segredo");
  }
}
