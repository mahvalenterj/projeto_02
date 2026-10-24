package br.gov.saude.upa.infrastructure.canais;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

class NotificadorEmailHttpTest extends NotificadorHttpContratoTest {

  @Override
  protected String caminho() {
    return "/v1/emails";
  }

  @Override
  protected String situacaoDeSucesso() {
    return "ACEITO";
  }

  @Override
  protected String destino() {
    return "paciente@example.org";
  }

  @Override
  protected String corpoEsperado(String texto) {
    return "{\"destinatario\":\""
        + destino()
        + "\",\"assunto\":\"Aviso da UPA: equipamento indisponível\",\"texto\":\""
        + texto
        + "\"}";
  }

  @Override
  protected Enviador novoEnviador(CanaisProperties.Canal canal) {
    return new NotificadorEmailHttp(new CanaisProperties(canal, canal))::enviar;
  }

  @Test
  void naoDeveMostrarEmailNemTextoNoToStringDoPedido() {
    var pedido = new NotificadorEmailHttp.PedidoDeEmail(destino(), "assunto", MENSAGEM.texto());

    assertThat(pedido.toString()).doesNotContain(destino()).doesNotContain(MENSAGEM.texto());
  }

  @Test
  void deveSerComponentEnaoRepositoryParaNaoConverterExcecoesDePreCondicao() {
    assertThat(NotificadorEmailHttp.class.isAnnotationPresent(Component.class)).isTrue();
    assertThat(NotificadorEmailHttp.class.isAnnotationPresent(Repository.class)).isFalse();
  }
}
