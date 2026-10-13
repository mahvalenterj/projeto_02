package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.UUID;

class AgendamentoRaioXTest extends AgendamentoExameContratoTest {

  @Override
  AgendamentoExame criar(
      UUID id,
      UUID idPaciente,
      UUID idUpa,
      UUID idEquipamento,
      Instant criadoEm,
      Instant inicioExame) {
    return new AgendamentoRaioX(id, idPaciente, idUpa, idEquipamento, criadoEm, inicioExame);
  }
}
