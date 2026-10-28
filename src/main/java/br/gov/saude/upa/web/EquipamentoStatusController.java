package br.gov.saude.upa.web;

import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /api/v1/upas/{upaId}/equipamentos/{id}/status} (spec 001 §8). Só traduz HTTP para o
 * caso de uso: a autorização por UPA é de {@link AutorizacaoPorUpa} e os erros viram ProblemDetail
 * em {@link TratadorDeErros}. O log de auditoria leva só ids e o {@code sub} do operador.
 */
@RestController
public class EquipamentoStatusController {

  private static final Logger LOG = LoggerFactory.getLogger(EquipamentoStatusController.class);

  private final MarcarEquipamentoIndisponivel indisponivel;
  private final MarcarEquipamentoDisponivel disponivel;
  private final AutorizacaoPorUpa autorizacao;

  public EquipamentoStatusController(
      MarcarEquipamentoIndisponivel indisponivel,
      MarcarEquipamentoDisponivel disponivel,
      AutorizacaoPorUpa autorizacao) {
    this.indisponivel = Objects.requireNonNull(indisponivel, "indisponivel");
    this.disponivel = Objects.requireNonNull(disponivel, "disponivel");
    this.autorizacao = Objects.requireNonNull(autorizacao, "autorizacao");
  }

  @PatchMapping("/api/v1/upas/{upaId}/equipamentos/{id}/status")
  public ResponseEntity<Void> alterar(
      @PathVariable UUID upaId,
      @PathVariable UUID id,
      @RequestBody AlteracaoDeStatusRequest corpo,
      @AuthenticationPrincipal Jwt jwt) {
    autorizacao.exigirQuePodeOperar(jwt, upaId);
    StatusSolicitado status = StatusSolicitado.de(corpo.status());
    switch (status) {
      case INDISPONIVEL -> {
        UUID eventoId = indisponivel.executar(upaId, id);
        LOG.info(
            "Equipamento marcado INDISPONIVEL: upaId={}, equipamentoId={}, eventoId={}, operador={}",
            upaId,
            id,
            eventoId,
            jwt.getSubject());
      }
      case DISPONIVEL -> {
        disponivel.executar(upaId, id);
        LOG.info(
            "Equipamento marcado DISPONIVEL: upaId={}, equipamentoId={}, operador={}",
            upaId,
            id,
            jwt.getSubject());
      }
    }
    return ResponseEntity.noContent().build();
  }
}
