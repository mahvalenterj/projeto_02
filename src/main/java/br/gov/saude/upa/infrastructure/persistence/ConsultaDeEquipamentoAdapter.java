package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.ConsultaDeEquipamento;
import br.gov.saude.upa.application.EquipamentoInexistenteException;
import br.gov.saude.upa.domain.StatusEquipamento;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Leitura nova a cada chamada, sem cache: o lote relê o status antes de cada envio (ADR 0002). */
@Component
class ConsultaDeEquipamentoAdapter implements ConsultaDeEquipamento {

  private final EquipamentoJpaRepository equipamentos;

  ConsultaDeEquipamentoAdapter(EquipamentoJpaRepository equipamentos) {
    this.equipamentos = equipamentos;
  }

  @Override
  @Transactional(readOnly = true)
  public StatusEquipamento statusAtual(UUID idEquipamento) {
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    return equipamentos
        .buscarStatus(idEquipamento)
        .orElseThrow(() -> new EquipamentoInexistenteException(idEquipamento));
  }
}
