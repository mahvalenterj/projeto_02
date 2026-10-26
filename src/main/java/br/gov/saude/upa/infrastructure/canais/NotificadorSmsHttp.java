package br.gov.saude.upa.infrastructure.canais;

import br.gov.saude.upa.application.ChaveDeIdempotencia;
import br.gov.saude.upa.application.NotificadorSms;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.domain.MensagemDeAviso;
import org.springframework.stereotype.Component;

/**
 * Adapter do canal SMS: {@code POST /v1/mensagens} com {@code {"destinatario","texto"}}; sucesso é
 * {@code 202} com situação {@code ACEITA}. {@code @Component}, e não {@code @Repository}: o Spring
 * converteria as exceções de pré-condição e vazaria exceção do framework pela porta.
 */
@Component
class NotificadorSmsHttp implements NotificadorSms {

  private final ClienteDeCanal cliente;

  NotificadorSmsHttp(CanaisProperties canais) {
    this.cliente = new ClienteDeCanal("sms", canais.sms(), "/v1/mensagens", "ACEITA");
  }

  @Override
  public ResultadoDoEnvio enviar(
      String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave) {
    ClienteDeCanal.validar(destino, mensagem, chave);
    return cliente.enviar(new PedidoDeSms(destino, mensagem.texto()), chave);
  }

  /** O {@code toString} não mostra o telefone nem o texto. */
  record PedidoDeSms(String destinatario, String texto) {

    @Override
    public String toString() {
      return "PedidoDeSms[...]";
    }
  }
}
