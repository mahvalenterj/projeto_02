package br.gov.saude.upa.application;

import java.util.UUID;

/** Outra alteração da mesma UPA foi gravada antes (lock otimista); vira 409 na camada web. */
public class ConflitoDeConcorrenciaException extends RuntimeException {

  private final UUID idUpa;

  public ConflitoDeConcorrenciaException(UUID idUpa) {
    super("A UPA " + idUpa + " foi alterada por outra operação");
    this.idUpa = idUpa;
  }

  public UUID idUpa() {
    return idUpa;
  }
}
