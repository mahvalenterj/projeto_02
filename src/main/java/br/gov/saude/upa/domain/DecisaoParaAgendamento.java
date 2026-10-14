package br.gov.saude.upa.domain;

import java.util.Objects;

/** O que fazer com um agendamento ao processar um evento (ADR 0001). */
public sealed interface DecisaoParaAgendamento {

  /** Sem aviso anterior: inserir a reserva {@code PENDENTE} e seguir para o envio. */
  record Reservar() implements DecisaoParaAgendamento {}

  /** Reserva abandonada: renovar a reserva e seguir para o envio. */
  record Reassumir() implements DecisaoParaAgendamento {}

  /** Não enviar de novo. */
  record Pular(MotivoDoPulo motivo) implements DecisaoParaAgendamento {
    public Pular {
      Objects.requireNonNull(motivo, "motivo");
    }
  }
}
