package br.gov.saude.upa.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Linha de {@code revisao_manual}. Só leitura: a escrita é {@code ON CONFLICT DO NOTHING}. */
@Entity
@Table(name = "revisao_manual")
@IdClass(ChaveDaRevisaoManual.class)
class RevisaoManualJpa {

  @Id
  @Column(name = "evento_id")
  private UUID eventoId;

  @Id
  @Column(name = "agendamento_id")
  private UUID agendamentoId;

  @Column(name = "motivo", nullable = false)
  private String motivo;

  @Column(name = "registrado_em", nullable = false)
  private Instant registradoEm;

  protected RevisaoManualJpa() {}
}
