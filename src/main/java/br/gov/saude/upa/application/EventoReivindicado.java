package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import java.util.Objects;

/** Evento do outbox reivindicado por um despachante; {@code tentativas} já conta esta. */
public record EventoReivindicado(EquipamentoIndisponivelEvent evento, int tentativas) {

  public EventoReivindicado {
    Objects.requireNonNull(evento, "evento");
    if (tentativas < 1) {
      throw new IllegalArgumentException("tentativas deve ser pelo menos 1");
    }
  }
}
