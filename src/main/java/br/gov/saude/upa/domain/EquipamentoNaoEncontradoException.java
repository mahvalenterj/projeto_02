package br.gov.saude.upa.domain;

import java.util.UUID;

public class EquipamentoNaoEncontradoException extends RuntimeException {

  public EquipamentoNaoEncontradoException(UUID idEquipamento, UUID idUpa) {
    super("Equipamento " + idEquipamento + " não pertence à UPA " + idUpa);
  }
}
