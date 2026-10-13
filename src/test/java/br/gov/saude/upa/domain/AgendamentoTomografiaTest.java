package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.UUID;

class AgendamentoTomografiaTest extends AgendamentoExameContratoTest {

  @Override
  AgendamentoExame criar(
      UUID id,
      UUID idPaciente,
      UUID idUpa,
      UUID idEquipamento,
      Instant criadoEm,
      Instant inicioExame) {
    return new AgendamentoTomografia(id, idPaciente, idUpa, idEquipamento, criadoEm, inicioExame);
  }
}
