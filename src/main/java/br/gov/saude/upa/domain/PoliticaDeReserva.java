package br.gov.saude.upa.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decide, para cada agendamento de um evento, se reserva, reassume ou pula (ADR 0001). A revisão
 * manual é consultada primeiro: ela encerra o caso para aquele evento.
 */
public class PoliticaDeReserva {

  public DecisaoParaAgendamento decidir(
      Optional<AvisoPaciente> existente,
      boolean emRevisaoManual,
      Instant agora,
      Duration tempoLimite) {
    Objects.requireNonNull(existente, "existente");
    Objects.requireNonNull(agora, "agora");
    AvisoPaciente.exigirTempoLimiteValido(tempoLimite);

    if (emRevisaoManual) {
      return new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_REVISAO_MANUAL);
    }
    if (existente.isEmpty()) {
      return new DecisaoParaAgendamento.Reservar();
    }
    AvisoPaciente aviso = existente.get();
    return switch (aviso.status()) {
      case CONFIRMADO -> new DecisaoParaAgendamento.Pular(MotivoDoPulo.JA_CONFIRMADO);
      case CANCELADO -> new DecisaoParaAgendamento.Pular(MotivoDoPulo.JA_CANCELADO);
      case PENDENTE ->
          aviso.estaAbandonada(agora, tempoLimite)
              ? new DecisaoParaAgendamento.Reassumir()
              : new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_ANDAMENTO);
    };
  }
}
