package br.gov.saude.upa.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Chave composta {@code (evento_id, agendamento_id)} de {@code aviso_paciente}. */
class ChaveDoAviso implements Serializable {

  private UUID eventoId;
  private UUID agendamentoId;

  protected ChaveDoAviso() {}

  ChaveDoAviso(UUID eventoId, UUID agendamentoId) {
    this.eventoId = eventoId;
    this.agendamentoId = agendamentoId;
  }

  @Override
  public boolean equals(Object outro) {
    return outro instanceof ChaveDoAviso chave
        && Objects.equals(eventoId, chave.eventoId)
        && Objects.equals(agendamentoId, chave.agendamentoId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(eventoId, agendamentoId);
  }
}
