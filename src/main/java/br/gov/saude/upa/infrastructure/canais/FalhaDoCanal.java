package br.gov.saude.upa.infrastructure.canais;

import java.time.Duration;
import java.util.Optional;

/**
 * Falha de uma tentativa de envio, classificada para o retry e o circuit breaker. Guarda só o tipo
 * e o status HTTP: nunca o corpo, o destino, o texto nem o token. Sem stack trace (é fluxo normal).
 */
final class FalhaDoCanal extends RuntimeException {

  /** O que o resto da cadeia deve fazer com a falha. */
  enum Tipo {
    /**
     * Vale repetir (5xx, 429 com espera aceitável, timeout, conexão derrubada); conta no breaker.
     */
    TRANSITORIA,
    /** Repetir não adianta, mas afeta todos os pacientes (401, 403, 429 acima do teto): conta. */
    SEM_RETRY,
    /**
     * Resposta fora do contrato, ilegível ou truncada: a mensagem pode ter sido aceita. Não repete,
     * não passa ao próximo canal (vira {@code Indeterminado}) e conta como falha no breaker.
     */
    INDETERMINADA,
    /** Erro do pedido de um paciente só (demais 4xx): não repete e não conta no breaker. */
    DO_PEDIDO
  }

  private static final long serialVersionUID = 1L;

  private final Tipo tipo;
  private final transient Duration esperaSugerida;

  private FalhaDoCanal(Tipo tipo, String descricao, Duration esperaSugerida) {
    super(descricao, null, false, false);
    this.tipo = tipo;
    this.esperaSugerida = esperaSugerida;
  }

  static FalhaDoCanal transitoria(String descricao, Duration esperaSugerida) {
    return new FalhaDoCanal(Tipo.TRANSITORIA, descricao, esperaSugerida);
  }

  static FalhaDoCanal semRetry(String descricao) {
    return new FalhaDoCanal(Tipo.SEM_RETRY, descricao, null);
  }

  static FalhaDoCanal indeterminada(String descricao) {
    return new FalhaDoCanal(Tipo.INDETERMINADA, descricao, null);
  }

  static FalhaDoCanal doPedido(String descricao) {
    return new FalhaDoCanal(Tipo.DO_PEDIDO, descricao, null);
  }

  Tipo tipo() {
    return tipo;
  }

  /** Espera pedida pelo provedor ({@code Retry-After}), quando houver. */
  Optional<Duration> esperaSugerida() {
    return Optional.ofNullable(esperaSugerida);
  }
}
