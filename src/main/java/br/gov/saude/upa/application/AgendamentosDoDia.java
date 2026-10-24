package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.CandidatoAAviso;
import java.util.List;
import java.util.Objects;

/**
 * Resultado da leitura dos agendamentos do equipamento no dia: os candidatos ao aviso e os
 * agendamentos descartados por dado inconsistente, que precisam de revisão manual.
 */
public record AgendamentosDoDia(
    List<CandidatoAAviso> candidatos, List<AgendamentoDescartado> descartados) {

  public AgendamentosDoDia {
    candidatos = List.copyOf(Objects.requireNonNull(candidatos, "candidatos"));
    descartados = List.copyOf(Objects.requireNonNull(descartados, "descartados"));
  }
}
