package br.gov.saude.upa.domain;

import java.util.Objects;

/**
 * Desfecho de uma tentativa de aviso. Use {@code switch} sem {@code default}: um novo subtipo deve
 * quebrar a compilação.
 */
public sealed interface ResultadoNotificacao {

  /** O canal confirmou o envio. */
  record Enviada() implements ResultadoNotificacao {}

  /** Não enviado porque o equipamento voltou antes do envio. */
  record Cancelada(MotivoCancelamento motivo) implements ResultadoNotificacao {
    public Cancelada {
      Objects.requireNonNull(motivo, "motivo");
    }
  }

  /** Estado desconhecido do canal: nunca conta como notificado. */
  record Indeterminada() implements ResultadoNotificacao {}

  /** Sem como avisar: o caso vai para a fila de revisão manual. */
  record RevisaoManual(MotivoRevisaoManual motivo) implements ResultadoNotificacao {
    public RevisaoManual {
      Objects.requireNonNull(motivo, "motivo");
    }
  }
}
