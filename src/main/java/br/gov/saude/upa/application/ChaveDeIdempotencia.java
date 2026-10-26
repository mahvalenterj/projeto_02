package br.gov.saude.upa.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifica um aviso (evento, agendamento) para o provedor do canal reconhecer a repetição da
 * mesma operação. Só ids: nenhum dado de paciente. O {@code toString} do record mostra apenas os
 * dois ids.
 */
public record ChaveDeIdempotencia(UUID eventoId, UUID agendamentoId) {

  public ChaveDeIdempotencia {
    Objects.requireNonNull(eventoId, "eventoId");
    Objects.requireNonNull(agendamentoId, "agendamentoId");
  }
}
