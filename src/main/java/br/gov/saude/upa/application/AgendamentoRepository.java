package br.gov.saude.upa.application;

import java.time.LocalDate;
import java.util.UUID;

/** Porta de saída dos agendamentos candidatos ao aviso. */
public interface AgendamentoRepository {

  /**
   * Pré-filtro para a consulta ao banco: agendamentos do equipamento no dia (America/Sao_Paulo). A
   * regra de quem entra no lote e em que ordem continua sendo do domínio ({@code SelecaoDeAvisos}).
   * Agendamento que não vira candidato (paciente inexistente, tipo de exame desconhecido) volta em
   * {@code descartados}, para o caso de uso encaminhá-lo à revisão manual.
   */
  AgendamentosDoDia buscarDoEquipamentoNoDia(UUID idEquipamento, LocalDate dia);
}
