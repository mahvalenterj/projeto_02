package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.MotivoCancelamento;
import br.gov.saude.upa.domain.StatusAviso;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Linha de {@code aviso_paciente}. Só leitura: as escritas são SQL condicional no repositório. */
@Entity
@Table(name = "aviso_paciente")
@IdClass(ChaveDoAviso.class)
class AvisoPacienteJpa {

  @Id
  @Column(name = "evento_id")
  private UUID eventoId;

  @Id
  @Column(name = "agendamento_id")
  private UUID agendamentoId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private StatusAviso status;

  @Enumerated(EnumType.STRING)
  @Column(name = "motivo_cancelamento")
  private MotivoCancelamento motivoCancelamento;

  @Column(name = "reservado_em", nullable = false)
  private Instant reservadoEm;

  @Column(name = "atualizado_em", nullable = false)
  private Instant atualizadoEm;

  protected AvisoPacienteJpa() {}

  UUID eventoId() {
    return eventoId;
  }

  UUID agendamentoId() {
    return agendamentoId;
  }

  StatusAviso status() {
    return status;
  }

  MotivoCancelamento motivoCancelamento() {
    return motivoCancelamento;
  }

  Instant reservadoEm() {
    return reservadoEm;
  }

  Instant atualizadoEm() {
    return atualizadoEm;
  }
}
