package br.gov.saude.upa.domain;

import java.util.Objects;
import java.util.UUID;

/** Entidade interna do agregado {@link UPA}: só o Aggregate Root altera o status. */
public class Equipamento {

  private final UUID id;
  private final TipoEquipamento tipo;
  private StatusEquipamento status;

  public Equipamento(UUID id, TipoEquipamento tipo, StatusEquipamento status) {
    this.id = Objects.requireNonNull(id, "id");
    this.tipo = Objects.requireNonNull(tipo, "tipo");
    this.status = Objects.requireNonNull(status, "status");
  }

  public UUID id() {
    return id;
  }

  public TipoEquipamento tipo() {
    return tipo;
  }

  public StatusEquipamento status() {
    return status;
  }

  void marcarIndisponivel() {
    if (status != StatusEquipamento.DISPONIVEL) {
      throw new TransicaoDeStatusInvalidaException(id, status, StatusEquipamento.INDISPONIVEL);
    }
    status = StatusEquipamento.INDISPONIVEL;
  }
}
