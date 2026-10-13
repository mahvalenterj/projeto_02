package br.gov.saude.upa.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Valor de leitura: um agendamento com a data de nascimento do paciente, necessária para ordenar o
 * lote. O {@code Paciente} segue como agregado próprio, referenciado só por id.
 */
public record CandidatoAAviso(AgendamentoExame agendamento, LocalDate dataNascimentoPaciente) {

  public CandidatoAAviso {
    Objects.requireNonNull(agendamento, "agendamento");
    Objects.requireNonNull(dataNascimentoPaciente, "dataNascimentoPaciente");
  }

  /** Só o id do agendamento: a data de nascimento é dado sensível e não vai para log. */
  @Override
  public String toString() {
    return "CandidatoAAviso[idAgendamento=" + agendamento.id() + "]";
  }
}
