package br.gov.saude.upa.application;

import java.util.UUID;

/** A consulta de status não achou o equipamento. */
public class EquipamentoInexistenteException extends RuntimeException {

  public EquipamentoInexistenteException(UUID idEquipamento) {
    super("Equipamento " + idEquipamento + " não encontrado");
  }
}
