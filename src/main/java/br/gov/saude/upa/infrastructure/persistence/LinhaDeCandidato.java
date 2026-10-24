package br.gov.saude.upa.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Resultado da consulta de candidatos (agendamento + nascimento do paciente). {@code
 * dataNascimento} é {@code null} quando o paciente não existe (junção externa). O {@code toString}
 * não mostra o nascimento.
 */
record LinhaDeCandidato(
    UUID id,
    String tipo,
    UUID idPaciente,
    UUID idUpa,
    UUID idEquipamento,
    Instant criadoEm,
    Instant inicioExame,
    LocalDate dataNascimento) {

  @Override
  public String toString() {
    return "LinhaDeCandidato[id=" + id + "]";
  }
}
