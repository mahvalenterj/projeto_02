package br.gov.saude.upa.web;

/** Corpo sem o campo obrigatório: vira 400, igual ao corpo malformado. */
class CorpoInvalidoException extends RuntimeException {

  CorpoInvalidoException() {
    super("Corpo da requisição sem status");
  }
}
