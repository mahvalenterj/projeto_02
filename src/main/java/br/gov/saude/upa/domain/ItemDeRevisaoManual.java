package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso encaminhado à revisão manual para um evento (ADR 0001). A identidade é o par {@code
 * (eventoId, agendamentoId)}; a revisão manual encerra o caso para aquele evento.
 */
public record ItemDeRevisaoManual(
    UUID eventoId, UUID agendamentoId, MotivoRevisaoManual motivo, Instant registradoEm) {

  public ItemDeRevisaoManual {
    Objects.requireNonNull(eventoId, "eventoId");
    Objects.requireNonNull(agendamentoId, "agendamentoId");
    Objects.requireNonNull(motivo, "motivo");
    Objects.requireNonNull(registradoEm, "registradoEm");
  }

  /** Só os ids: o item se liga a paciente e exame, que são dados sensíveis. */
  @Override
  public String toString() {
    return "ItemDeRevisaoManual[eventoId=" + eventoId + ", agendamentoId=" + agendamentoId + "]";
  }
}
