package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.MensagemDeAviso;

/** Porta de saída do canal de e-mail, com o mesmo contrato do {@link NotificadorSms}. */
public interface NotificadorEmail {

  ResultadoDoEnvio enviar(String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave);
}
