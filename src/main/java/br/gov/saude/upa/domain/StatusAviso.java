package br.gov.saude.upa.domain;

/** Status da linha de aviso (ADR 0001). Só {@code PENDENTE} evolui; os outros são terminais. */
public enum StatusAviso {
  PENDENTE,
  CONFIRMADO,
  CANCELADO;

  /** Sem {@code default}: novo status quebra a compilação. */
  public boolean podeTransicionarPara(StatusAviso destino) {
    return switch (this) {
      case PENDENTE -> destino == CONFIRMADO || destino == CANCELADO;
      case CONFIRMADO, CANCELADO -> false;
    };
  }
}
