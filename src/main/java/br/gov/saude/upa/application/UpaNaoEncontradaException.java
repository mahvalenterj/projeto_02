package br.gov.saude.upa.application;

import java.util.UUID;

public class UpaNaoEncontradaException extends RuntimeException {

  private final UUID idUpa;

  public UpaNaoEncontradaException(UUID idUpa) {
    super("UPA " + idUpa + " não encontrada");
    this.idUpa = idUpa;
  }

  public UUID idUpa() {
    return idUpa;
  }
}
