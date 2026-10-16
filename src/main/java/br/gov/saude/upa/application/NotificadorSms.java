package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.MensagemDeAviso;

/**
 * Porta de saída do canal SMS. Retry, circuit breaker e limites ficam dentro do adapter: a porta só
 * devolve o desfecho final do canal.
 */
public interface NotificadorSms {

  ResultadoDoEnvio enviar(String destino, MensagemDeAviso mensagem);
}
