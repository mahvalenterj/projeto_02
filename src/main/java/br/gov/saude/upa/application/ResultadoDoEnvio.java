package br.gov.saude.upa.application;

/**
 * Desfecho final de um canal. Use {@code switch} sem {@code default}: um novo subtipo deve quebrar
 * a compilação.
 */
public sealed interface ResultadoDoEnvio {

  /** O canal confirmou o envio. */
  record Confirmado() implements ResultadoDoEnvio {}

  /** Tentativas esgotadas ou circuit breaker aberto: vale tentar o próximo canal. */
  record Esgotado() implements ResultadoDoEnvio {}

  /**
   * Resposta ilegível ou fora do contrato: a mensagem pode ter sido entregue, então a cadeia para
   * (um segundo canal poderia duplicar o aviso).
   */
  record Indeterminado() implements ResultadoDoEnvio {}
}
