package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.StatusEquipamento;
import java.util.UUID;

/** Porta de saída que lê o status do equipamento; cada chamada é uma leitura nova, sem cache. */
public interface ConsultaDeEquipamento {

  StatusEquipamento statusAtual(UUID idEquipamento);
}
