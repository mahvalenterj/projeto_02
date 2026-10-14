package br.gov.saude.upa.domain;

import java.util.Objects;

/** Endereço para onde o paciente deve ir quando o equipamento da UPA está indisponível. */
public record EnderecoAlternativo(String texto) {

  public EnderecoAlternativo {
    Objects.requireNonNull(texto, "texto");
    if (texto.isBlank()) {
      throw new IllegalArgumentException("texto do endereço alternativo não pode ser vazio");
    }
  }
}
