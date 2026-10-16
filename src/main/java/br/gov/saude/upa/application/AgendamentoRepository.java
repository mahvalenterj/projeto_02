package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.CandidatoAAviso;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Porta de saída dos agendamentos candidatos ao aviso. */
public interface AgendamentoRepository {

  /**
   * Pré-filtro para a consulta ao banco: agendamentos do equipamento no dia (America/Sao_Paulo). A
   * regra de quem entra no lote e em que ordem continua sendo do domínio ({@code SelecaoDeAvisos}).
   */
  List<CandidatoAAviso> buscarDoEquipamentoNoDia(UUID idEquipamento, LocalDate dia);
}
