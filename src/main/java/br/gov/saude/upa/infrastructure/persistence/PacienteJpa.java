package br.gov.saude.upa.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha de {@code paciente}, só leitura (quem grava é o sistema de regulação). Nome, contatos e
 * nascimento são dados sensíveis: o {@code toString} mostra só o id.
 */
@Entity
@Table(name = "paciente")
class PacienteJpa {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "nome", nullable = false)
  private String nome;

  @Column(name = "telefone")
  private String telefone;

  @Column(name = "email")
  private String email;

  @Column(name = "data_nascimento", nullable = false)
  private LocalDate dataNascimento;

  protected PacienteJpa() {}

  UUID id() {
    return id;
  }

  @Override
  public String toString() {
    return "PacienteJpa[id=" + id + "]";
  }
}
