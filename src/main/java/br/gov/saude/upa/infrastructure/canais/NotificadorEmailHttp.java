package br.gov.saude.upa.infrastructure.canais;

import br.gov.saude.upa.application.ChaveDeIdempotencia;
import br.gov.saude.upa.application.NotificadorEmail;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.domain.MensagemDeAviso;
import org.springframework.stereotype.Component;

/**
 * Adapter do canal de e-mail: {@code POST /v1/emails} com {@code {"destinatario","assunto",
 * "texto"}} e assunto fixo e genérico; sucesso é {@code 202} com situação {@code ACEITO}.
 * {@code @Component}, e não {@code @Repository} (ver {@link NotificadorSmsHttp}).
 */
@Component
class NotificadorEmailHttp implements NotificadorEmail {

  /** Genérico de propósito: o assunto aparece na caixa de entrada sem abrir a mensagem. */
  static final String ASSUNTO = "Aviso da UPA: equipamento indisponível";

  private final ClienteDeCanal cliente;

  NotificadorEmailHttp(CanaisProperties canais) {
    this.cliente = new ClienteDeCanal("email", canais.email(), "/v1/emails", "ACEITO");
  }

  @Override
  public ResultadoDoEnvio enviar(
      String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave) {
    ClienteDeCanal.validar(destino, mensagem, chave);
    return cliente.enviar(new PedidoDeEmail(destino, ASSUNTO, mensagem.texto()), chave);
  }

  /** O {@code toString} não mostra o e-mail nem o texto. */
  record PedidoDeEmail(String destinatario, String assunto, String texto) {

    @Override
    public String toString() {
      return "PedidoDeEmail[...]";
    }
  }
}
