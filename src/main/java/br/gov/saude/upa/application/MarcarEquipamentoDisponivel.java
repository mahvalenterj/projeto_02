package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.UPA;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Devolve o equipamento a DISPONIVEL. Sem evento: o lote relê o status antes de cada envio. */
public class MarcarEquipamentoDisponivel {

  private final UPAPersistencia upas;

  public MarcarEquipamentoDisponivel(UPAPersistencia upas) {
    this.upas = Objects.requireNonNull(upas, "upas");
  }

  @Transactional
  public void executar(UUID idUpa, UUID idEquipamento) {
    Objects.requireNonNull(idUpa, "idUpa");
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    UPA upa = upas.buscar(idUpa).orElseThrow(() -> new UpaNaoEncontradaException(idUpa));

    upa.marcarEquipamentoDisponivel(idEquipamento);
    upas.salvar(upa);
  }
}
