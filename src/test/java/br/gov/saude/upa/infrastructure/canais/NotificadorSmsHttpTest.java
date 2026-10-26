package br.gov.saude.upa.infrastructure.canais;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

class NotificadorSmsHttpTest extends NotificadorHttpContratoTest {

  @Override
  protected String nomeDoCanal() {
    return "sms";
  }

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

  @Test
  void deveEnviarOCabecalhoLiteralDeIdempotenciaDoCanalSms() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"msg-1\",\"situacao\":\"" + situacaoDeSucesso() + "\"}")));

    novoEnviador(canal().build()).enviar(destino(), MENSAGEM, CHAVE);

    provedor.verify(
        1,
        postRequestedFor(urlPathEqualTo("/v1/mensagens"))
            .withHeader(
                "Idempotency-Key",
                equalTo(
                    "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222:sms")));
  }
}
