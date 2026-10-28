package br.gov.saude.upa.web;

import java.util.UUID;

/**
 * O operador não opera esta UPA (ela não está no claim {@code upas}). Vira o MESMO 404 do recurso
 * inexistente (CA13, ADR 0004): não se usa 403, para não revelar quais UPAs existem.
 */
public class UpaForaDoAlcanceException extends RuntimeException {

  private final UUID idUpa;

  public UpaForaDoAlcanceException(UUID idUpa) {
    super("UPA " + idUpa + " fora do alcance do operador");
    this.idUpa = idUpa;
  }

  public UUID idUpa() {
    return idUpa;
  }
}
