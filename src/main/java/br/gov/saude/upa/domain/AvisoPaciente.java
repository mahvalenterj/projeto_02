package br.gov.saude.upa.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Linha de aviso de um agendamento para um evento (ADR 0001). A identidade é o par {@code
 * (eventoId, agendamentoId)}. Nasce {@code PENDENTE} (reserva) e só vira {@code CONFIRMADO} com a
 * confirmação do canal, ou {@code CANCELADO} se o equipamento voltar antes do envio.
 */
public class AvisoPaciente {

  private final UUID eventoId;
  private final UUID agendamentoId;
  private StatusAviso status;
  private Instant reservadoEm;
  private Instant atualizadoEm;
  private MotivoCancelamento motivoCancelamento;

  private AvisoPaciente(UUID eventoId, UUID agendamentoId, Instant agora) {
    this.eventoId = Objects.requireNonNull(eventoId, "eventoId");
    this.agendamentoId = Objects.requireNonNull(agendamentoId, "agendamentoId");
    Objects.requireNonNull(agora, "agora");
    this.status = StatusAviso.PENDENTE;
    this.reservadoEm = agora;
    this.atualizadoEm = agora;
  }

  public static AvisoPaciente reservar(UUID eventoId, UUID agendamentoId, Instant agora) {
    return new AvisoPaciente(eventoId, agendamentoId, agora);
  }

  public UUID eventoId() {
    return eventoId;
  }

  public UUID agendamentoId() {
    return agendamentoId;
  }

  public StatusAviso status() {
    return status;
  }

  public Instant reservadoEm() {
    return reservadoEm;
  }

  public Instant atualizadoEm() {
    return atualizadoEm;
  }

  public Optional<MotivoCancelamento> motivoCancelamento() {
    return Optional.ofNullable(motivoCancelamento);
  }

  /**
   * Registra o desfecho de uma tentativa. {@code Indeterminada} e {@code RevisaoManual} não mudam a
   * linha: ela continua {@code PENDENTE} e nunca vira {@code CONFIRMADO}.
   */
  public void aplicar(ResultadoNotificacao resultado, Instant agora) {
    Objects.requireNonNull(resultado, "resultado");
    Objects.requireNonNull(agora, "agora");
    switch (resultado) {
      case ResultadoNotificacao.Enviada enviada ->
          transicionarPara(StatusAviso.CONFIRMADO, null, agora);
      case ResultadoNotificacao.Cancelada cancelada ->
          transicionarPara(StatusAviso.CANCELADO, cancelada.motivo(), agora);
      case ResultadoNotificacao.Indeterminada indeterminada -> exigirPendente();
      case ResultadoNotificacao.RevisaoManual revisaoManual -> exigirPendente();
    }
  }

  /** Reserva {@code PENDENTE} mais antiga que o tempo-limite: o processo caiu no meio do envio. */
  public boolean estaAbandonada(Instant agora, Duration tempoLimite) {
    Objects.requireNonNull(agora, "agora");
    exigirTempoLimiteValido(tempoLimite);
    return status == StatusAviso.PENDENTE
        && Duration.between(reservadoEm, agora).compareTo(tempoLimite) > 0;
  }

  public void reassumir(Instant agora, Duration tempoLimite) {
    if (!estaAbandonada(agora, tempoLimite)) {
      throw new TransicaoDeAvisoInvalidaException(
          eventoId, agendamentoId, "reserva não está abandonada (status " + status + ")");
    }
    reservadoEm = agora;
    atualizadoEm = agora;
  }

  static void exigirTempoLimiteValido(Duration tempoLimite) {
    Objects.requireNonNull(tempoLimite, "tempoLimite");
    if (tempoLimite.isZero() || tempoLimite.isNegative()) {
      throw new IllegalArgumentException("tempoLimite deve ser positivo");
    }
  }

  private void transicionarPara(StatusAviso destino, MotivoCancelamento motivo, Instant agora) {
    if (!status.podeTransicionarPara(destino)) {
      throw new TransicaoDeAvisoInvalidaException(
          eventoId, agendamentoId, "status " + status + " não aceita novo resultado");
    }
    status = destino;
    motivoCancelamento = motivo;
    atualizadoEm = agora;
  }

  private void exigirPendente() {
    if (status != StatusAviso.PENDENTE) {
      throw new TransicaoDeAvisoInvalidaException(
          eventoId, agendamentoId, "status " + status + " não aceita novo resultado");
    }
  }

  /** Só ids: o aviso se liga a paciente e exame, que são dados sensíveis. */
  @Override
  public String toString() {
    return "AvisoPaciente[eventoId=" + eventoId + ", agendamentoId=" + agendamentoId + "]";
  }
}
