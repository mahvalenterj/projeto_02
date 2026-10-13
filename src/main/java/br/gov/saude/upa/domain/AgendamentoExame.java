package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato comum de todo agendamento de exame. Referencia paciente, UPA e equipamento só por id.
 * Novo tipo de exame = nova implementação, nunca {@code if/else} por tipo.
 */
public interface AgendamentoExame {

  UUID id();

  UUID idPaciente();

  UUID idUpa();

  UUID idEquipamento();

  Instant criadoEm();

  Instant inicioExame();
}
