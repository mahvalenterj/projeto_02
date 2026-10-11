package br.gov.saude.upa.domain;

import java.util.UUID;

public class TransicaoDeStatusInvalidaException extends RuntimeException {

  public TransicaoDeStatusInvalidaException(
      UUID idEquipamento, StatusEquipamento atual, StatusEquipamento desejado) {
    super("Equipamento " + idEquipamento + " não pode ir de " + atual + " para " + desejado);
  }
}
