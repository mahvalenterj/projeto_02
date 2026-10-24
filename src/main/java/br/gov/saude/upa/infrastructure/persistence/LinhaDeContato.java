package br.gov.saude.upa.infrastructure.persistence;

import java.util.UUID;

/** Resultado da consulta de contatos. O {@code toString} não mostra telefone nem e-mail. */
record LinhaDeContato(UUID id, String telefone, String email) {

  @Override
  public String toString() {
    return "LinhaDeContato[id=" + id + "]";
  }
}
