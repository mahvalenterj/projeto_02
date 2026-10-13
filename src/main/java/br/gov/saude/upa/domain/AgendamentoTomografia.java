package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgendamentoTomografia(
    UUID id, UUID idPaciente, UUID idUpa, UUID idEquipamento, Instant criadoEm, Instant inicioExame)
    implements AgendamentoExame {

  public AgendamentoTomografia {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(idPaciente, "idPaciente");
    Objects.requireNonNull(idUpa, "idUpa");
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    Objects.requireNonNull(criadoEm, "criadoEm");
    Objects.requireNonNull(inicioExame, "inicioExame");
  }

  /** Só o id: paciente e horário do exame são dados sensíveis e não vão para log. */
  @Override
  public String toString() {
    return "AgendamentoTomografia[id=" + id + "]";
  }
}
