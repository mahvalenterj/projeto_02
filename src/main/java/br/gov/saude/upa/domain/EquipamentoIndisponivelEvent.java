package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EquipamentoIndisponivelEvent(
    UUID eventoId, UUID idEquipamento, UUID idUpa, Instant momentoFalha) {

  public EquipamentoIndisponivelEvent {
    Objects.requireNonNull(eventoId, "eventoId");
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    Objects.requireNonNull(idUpa, "idUpa");
    Objects.requireNonNull(momentoFalha, "momentoFalha");
  }
}
