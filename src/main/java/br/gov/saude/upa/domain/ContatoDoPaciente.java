package br.gov.saude.upa.domain;

/**
 * Canais pelos quais o paciente pode ser avisado. Telefone e e-mail são opcionais ({@code null} =
 * não tem); se presentes, não podem ser em branco. São dados sensíveis: o {@code toString} não os
 * mostra.
 */
public record ContatoDoPaciente(String telefone, String email) {

  public ContatoDoPaciente {
    if (telefone != null && telefone.isBlank()) {
      throw new IllegalArgumentException("telefone, se informado, não pode ser em branco");
    }
    if (email != null && email.isBlank()) {
      throw new IllegalArgumentException("e-mail, se informado, não pode ser em branco");
    }
  }

  public boolean possuiTelefone() {
    return telefone != null;
  }

  public boolean possuiEmail() {
    return email != null;
  }

  @Override
  public String toString() {
    return "ContatoDoPaciente[...]";
  }
}
