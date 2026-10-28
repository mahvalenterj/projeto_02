package br.gov.saude.upa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import br.gov.saude.upa.application.ConflitoDeConcorrenciaException;
import br.gov.saude.upa.application.EquipamentoInexistenteException;
import br.gov.saude.upa.application.UpaNaoEncontradaException;
import br.gov.saude.upa.domain.EquipamentoNaoEncontradoException;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TransicaoDeStatusInvalidaException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contrato HTTP do PATCH de status (spec 001 §8, CA13): 204, 404 idêntico, 422, 409, 400, 500 e
 * 401, sempre em ProblemDetail sem detalhe interno. O token aqui vem do {@code jwt()} do
 * spring-security-test; a validação do token real fica em {@link SegurancaJwtTest}.
 */
class EquipamentoStatusControllerTest extends WebComSegurancaTest {

  private final UUID upa = UUID.randomUUID();
  private final UUID equipamento = UUID.randomUUID();

  private static JwtRequestPostProcessor operadorDe(UUID... upas) {
    List<String> lista = java.util.Arrays.stream(upas).map(UUID::toString).toList();
    return jwt().jwt(j -> j.subject("operador-1").claim("upas", lista));
  }

  private MockHttpServletResponse executar(MockHttpServletRequestBuilder requisicao)
      throws Exception {
    return mvc.perform(requisicao).andReturn().getResponse();
  }

  // ---------- sucesso ----------

  @Test
  void deveResponder204EMarcarIndisponivelQuandoOOperadorOperaAUpa() throws Exception {
    when(marcarIndisponivel.executar(upa, equipamento)).thenReturn(UUID.randomUUID());

    MockHttpServletResponse resposta =
        executar(patchStatus(upa, equipamento, "INDISPONIVEL").with(operadorDe(upa)));

    assertThat(resposta.getStatus()).isEqualTo(204);
    assertThat(resposta.getContentAsString()).isEmpty();
    verify(marcarIndisponivel).executar(upa, equipamento);
    verifyNoInteractions(marcarDisponivel);
  }

  @Test
  void deveResponder204EMarcarDisponivelQuandoOEquipamentoVolta() throws Exception {
    MockHttpServletResponse resposta =
        executar(
            patchStatus(upa, equipamento, "DISPONIVEL").with(operadorDe(UUID.randomUUID(), upa)));

    assertThat(resposta.getStatus()).isEqualTo(204);
    verify(marcarDisponivel).executar(upa, equipamento);
    verifyNoInteractions(marcarIndisponivel);
  }

  // ---------- 401 ----------

  @Test
  void deveResponder401EmProblemDetailQuandoNaoHaToken() throws Exception {
    MockHttpServletResponse resposta = executar(patchStatus(upa, equipamento, "INDISPONIVEL"));

    exigirProblemDetailLimpo(resposta, 401);
    assertThat(resposta.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"upa\"");
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);
  }

  // ---------- 404 idêntico (CA13) ----------

  @Test
  void ca13_deveResponder404IgualParaOutraUpaE409EmConflito() throws Exception {
    // UPA fora do claim: a checagem vem antes de qualquer consulta.
    MockHttpServletResponse foraDoToken =
        executar(patchStatus(upa, equipamento, "INDISPONIVEL").with(operadorDe(UUID.randomUUID())));
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);

    UUID inexistente = UUID.randomUUID();
    when(marcarIndisponivel.executar(inexistente, equipamento))
        .thenThrow(new UpaNaoEncontradaException(inexistente));
    MockHttpServletResponse upaInexistente =
        executar(
            patchStatus(inexistente, equipamento, "INDISPONIVEL").with(operadorDe(inexistente)));

    UUID deOutraUpa = UUID.randomUUID();
    when(marcarIndisponivel.executar(upa, deOutraUpa))
        .thenThrow(new EquipamentoNaoEncontradoException(deOutraUpa, upa));
    MockHttpServletResponse equipamentoDeOutraUpa =
        executar(patchStatus(upa, deOutraUpa, "INDISPONIVEL").with(operadorDe(upa)));

    UUID semCadastro = UUID.randomUUID();
    doThrow(new EquipamentoInexistenteException(semCadastro))
        .when(marcarDisponivel)
        .executar(upa, semCadastro);
    MockHttpServletResponse equipamentoInexistente =
        executar(patchStatus(upa, semCadastro, "DISPONIVEL").with(operadorDe(upa)));

    MockHttpServletResponse idMalformado =
        executar(
            patch("/api/v1/upas/nao-e-uuid/equipamentos/" + equipamento + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INDISPONIVEL\"}")
                .with(operadorDe(upa)));

    List<MockHttpServletResponse> todas =
        List.of(foraDoToken, upaInexistente, equipamentoDeOutraUpa, equipamentoInexistente);
    for (MockHttpServletResponse resposta : todas) {
      exigirProblemDetailLimpo(resposta, 404);
      assertThat(corpoSemInstance(resposta)).isEqualTo(corpoSemInstance(foraDoToken));
      assertThat(cabecalhos(resposta)).isEqualTo(cabecalhos(foraDoToken));
      assertThat(JSON.writeValueAsString(corpoSemInstance(resposta)))
          .doesNotContain(upa.toString());
    }
    exigirProblemDetailLimpo(idMalformado, 404);
    assertThat(corpoSemInstance(idMalformado)).isEqualTo(corpoSemInstance(foraDoToken));
    assertThat(corpo(foraDoToken))
        .containsEntry("instance", caminho(upa, equipamento))
        .containsEntry("title", "Recurso não encontrado");

    // 409: conflito de versão (lock otimista) no mesmo contrato.
    UUID disputado = UUID.randomUUID();
    when(marcarIndisponivel.executar(upa, disputado))
        .thenThrow(new ConflitoDeConcorrenciaException(upa));
    MockHttpServletResponse conflito =
        executar(patchStatus(upa, disputado, "INDISPONIVEL").with(operadorDe(upa)));
    exigirProblemDetailLimpo(conflito, 409);
    assertThat(JSON.writeValueAsString(corpoSemInstance(conflito))).doesNotContain(upa.toString());
  }

  @Test
  void ca13_naoDeveConsultarNadaQuandoOTokenNaoTemOClaimUpas() throws Exception {
    MockHttpServletResponse semClaim =
        executar(
            patchStatus(upa, equipamento, "INDISPONIVEL")
                .with(jwt().jwt(j -> j.subject("operador-1"))));
    MockHttpServletResponse claimInvalido =
        executar(
            patchStatus(upa, equipamento, "INDISPONIVEL")
                .with(jwt().jwt(j -> j.subject("operador-1").claim("upas", upa.toString()))));
    MockHttpServletResponse uuidInvalidoNaLista =
        executar(
            patchStatus(upa, equipamento, "INDISPONIVEL")
                .with(jwt().jwt(j -> j.claim("upas", List.of("nao-e-uuid", 42)))));

    for (MockHttpServletResponse resposta : List.of(semClaim, claimInvalido, uuidInvalidoNaLista)) {
      exigirProblemDetailLimpo(resposta, 404);
    }
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);
  }

  @Test
  void ca13_deveAceitarOUuidDaUpaEmMaiusculasNoClaim() throws Exception {
    MockHttpServletResponse resposta =
        executar(
            patchStatus(upa, equipamento, "DISPONIVEL")
                .with(jwt().jwt(j -> j.claim("upas", List.of(upa.toString().toUpperCase())))));

    assertThat(resposta.getStatus()).isEqualTo(204);
  }

  // ---------- 422 (CA13) ----------

  @Test
  void ca13_deveResponder422QuandoATransicaoEInvalida() throws Exception {
    when(marcarIndisponivel.executar(upa, equipamento))
        .thenThrow(
            new TransicaoDeStatusInvalidaException(
                equipamento, StatusEquipamento.INDISPONIVEL, StatusEquipamento.INDISPONIVEL));

    MockHttpServletResponse resposta =
        executar(patchStatus(upa, equipamento, "INDISPONIVEL").with(operadorDe(upa)));

    exigirProblemDetailLimpo(resposta, 422);
    assertThat(JSON.writeValueAsString(corpoSemInstance(resposta)))
        .doesNotContain(equipamento.toString());
  }

  @Test
  void ca13_deveResponder422SemConsultarNadaQuandoOStatusEEmManutencaoOuDesconhecido()
      throws Exception {
    MockHttpServletResponse manutencao =
        executar(patchStatus(upa, equipamento, "EM_MANUTENCAO").with(operadorDe(upa)));
    MockHttpServletResponse desconhecido =
        executar(patchStatus(upa, equipamento, "QUEBRADO").with(operadorDe(upa)));
    MockHttpServletResponse minusculo =
        executar(patchStatus(upa, equipamento, "indisponivel").with(operadorDe(upa)));

    for (MockHttpServletResponse resposta : List.of(manutencao, desconhecido, minusculo)) {
      exigirProblemDetailLimpo(resposta, 422);
      assertThat(corpoSemInstance(resposta)).isEqualTo(corpoSemInstance(manutencao));
      assertThat(resposta.getContentAsString()).doesNotContain("QUEBRADO");
    }
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);
  }

  @Test
  void ca13_deveResponder404AntesDe422QuandoAUpaNaoEstaNoToken() throws Exception {
    MockHttpServletResponse resposta =
        executar(
            patchStatus(upa, equipamento, "EM_MANUTENCAO").with(operadorDe(UUID.randomUUID())));

    exigirProblemDetailLimpo(resposta, 404);
  }

  // ---------- 400 ----------

  @Test
  void deveResponder400QuandoOCorpoEMalformado() throws Exception {
    MockHttpServletResponse malformado =
        executar(
            patch(caminho(upa, equipamento))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":")
                .with(operadorDe(upa)));
    MockHttpServletResponse semCorpo =
        executar(
            patch(caminho(upa, equipamento))
                .contentType(MediaType.APPLICATION_JSON)
                .with(operadorDe(upa)));
    MockHttpServletResponse semStatus =
        executar(
            patch(caminho(upa, equipamento))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(operadorDe(upa)));
    MockHttpServletResponse naoJson =
        executar(
            patch(caminho(upa, equipamento))
                .contentType(MediaType.TEXT_PLAIN)
                .content("INDISPONIVEL")
                .with(operadorDe(upa)));

    for (MockHttpServletResponse resposta : List.of(malformado, semCorpo, semStatus)) {
      exigirProblemDetailLimpo(resposta, 400);
      assertThat(corpoSemInstance(resposta)).isEqualTo(corpoSemInstance(malformado));
    }
    exigirProblemDetailLimpo(naoJson, 415);
    verifyNoInteractions(marcarIndisponivel, marcarDisponivel);
  }

  // ---------- 500 ----------

  @Test
  void deveResponder500GenericoSemVazarMensagemInterna() throws Exception {
    when(marcarIndisponivel.executar(any(), any()))
        .thenThrow(
            new IllegalStateException(
                "ERROR: duplicate key; SQL [SELECT nome FROM paciente WHERE nome = 'Fulano']"));

    MockHttpServletResponse resposta =
        executar(patchStatus(upa, equipamento, "INDISPONIVEL").with(operadorDe(upa)));

    exigirProblemDetailLimpo(resposta, 500);
    assertThat(resposta.getContentAsString())
        .doesNotContain("Fulano")
        .doesNotContain("duplicate")
        .doesNotContain("IllegalState");
    Map<String, Object> corpo = corpo(resposta);
    assertThat(corpo).containsEntry("title", "Erro interno");
  }

  @Test
  void deveResponder405EmProblemDetailParaMetodoNaoSuportado() throws Exception {
    MockHttpServletResponse resposta =
        executar(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    caminho(upa, equipamento))
                .with(operadorDe(upa)));

    exigirProblemDetailLimpo(resposta, 405);
  }
}
