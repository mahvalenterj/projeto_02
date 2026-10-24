package br.gov.saude.upa.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agendamento do dia que a leitura não conseguiu transformar em candidato (paciente inexistente ou
 * tipo de exame desconhecido). Só o id e o horário: nenhum dado do paciente.
 */
public record AgendamentoDescartado(UUID idAgendamento, Instant inicioExame) {

  public AgendamentoDescartado {
    Objects.requireNonNull(idAgendamento, "idAgendamento");
    Objects.requireNonNull(inicioExame, "inicioExame");
  }
}
