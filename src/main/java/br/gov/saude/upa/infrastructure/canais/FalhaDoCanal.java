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

  private static final String CAUSA_DESCONHECIDA = "desconhecida";

  private final Tipo tipo;
  private final transient Duration esperaSugerida;
  private final String causa;

  private FalhaDoCanal(Tipo tipo, String descricao, Duration esperaSugerida, String causa) {
    super(descricao, null, false, false);
    this.tipo = tipo;
    this.esperaSugerida = esperaSugerida;
    this.causa = causa == null ? CAUSA_DESCONHECIDA : causa;
  }

  static FalhaDoCanal transitoria(String descricao, Duration esperaSugerida) {
    return transitoria(descricao, esperaSugerida, null);
  }

  /**
   * @param causa nome da classe da exceção de origem ou {@code HTTP <status>}; nunca a mensagem
   */
  static FalhaDoCanal transitoria(String descricao, Duration esperaSugerida, String causa) {
    return new FalhaDoCanal(Tipo.TRANSITORIA, descricao, esperaSugerida, causa);
  }

  static FalhaDoCanal semRetry(String descricao) {
    return new FalhaDoCanal(Tipo.SEM_RETRY, descricao, null, null);
  }

  static FalhaDoCanal indeterminada(String descricao) {
    return new FalhaDoCanal(Tipo.INDETERMINADA, descricao, null, null);
  }

  static FalhaDoCanal doPedido(String descricao) {
    return new FalhaDoCanal(Tipo.DO_PEDIDO, descricao, null, null);
  }

  Tipo tipo() {
    return tipo;
  }

  /** Nome da classe da exceção de origem, ou {@code HTTP <status>}: seguro para log. */
  String causa() {
    return causa;
  }

  /** Espera pedida pelo provedor ({@code Retry-After}), quando houver. */
  Optional<Duration> esperaSugerida() {
    return Optional.ofNullable(esperaSugerida);
  }
}
