package br.gov.saude.upa.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Linha de {@code agendamento_exame}, só leitura. Referencia paciente, UPA e equipamento só por id
 * (sem chave estrangeira). O {@code toString} mostra só o id.
 */
@Entity
@Table(name = "agendamento_exame")
class AgendamentoExameJpa {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "tipo", nullable = false)
  private String tipo;

  @Column(name = "paciente_id", nullable = false)
  private UUID pacienteId;

  @Column(name = "upa_id", nullable = false)
  private UUID upaId;

  @Column(name = "equipamento_id", nullable = false)
  private UUID equipamentoId;

  @Column(name = "criado_em", nullable = false)
  private Instant criadoEm;

  @Column(name = "inicio_exame", nullable = false)
  private Instant inicioExame;

  protected AgendamentoExameJpa() {}

  UUID id() {
    return id;
  }

  @Override
  public String toString() {
    return "AgendamentoExameJpa[id=" + id + "]";
  }
}
