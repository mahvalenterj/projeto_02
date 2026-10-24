package br.gov.saude.upa.domain;

public enum MotivoRevisaoManual {
  SEM_CONTATO,
  CANAIS_ESGOTADOS,
  /** Agendamento sem paciente cadastrado ou de tipo de exame desconhecido. */
  DADO_INCONSISTENTE
}
