package br.gov.saude.upa.infrastructure.canais;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

class NotificadorSmsHttpTest extends NotificadorHttpContratoTest {

  @Override
  protected String caminho() {
    return "/v1/mensagens";
  }

  @Override
  protected String situacaoDeSucesso() {
    return "ACEITA";
  }

  @Override
  protected String destino() {
    return "+5521999990000";
  }

  @Override
  protected String corpoEsperado(String texto) {
    return "{\"destinatario\":\"" + destino() + "\",\"texto\":\"" + texto + "\"}";
  }

  @Override
  protected Enviador novoEnviador(CanaisProperties.Canal canal) {
    return new NotificadorSmsHttp(new CanaisProperties(canal, canal))::enviar;
  }

  @Test
  void naoDeveMostrarTelefoneNemTextoNoToStringDoPedido() {
    var pedido = new NotificadorSmsHttp.PedidoDeSms(destino(), MENSAGEM.texto());

    assertThat(pedido.toString()).doesNotContain(destino()).doesNotContain(MENSAGEM.texto());
  }

  @Test
  void deveSerComponentEnaoRepositoryParaNaoConverterExcecoesDePreCondicao() {
    assertThat(NotificadorSmsHttp.class.isAnnotationPresent(Component.class)).isTrue();
    assertThat(NotificadorSmsHttp.class.isAnnotationPresent(Repository.class)).isFalse();
  }
}
