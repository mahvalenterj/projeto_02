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

class NotificadorEmailHttpTest extends NotificadorHttpContratoTest {

  @Override
  protected String nomeDoCanal() {
    return "email";
  }

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

  @Test
  void deveEnviarOCabecalhoLiteralDeIdempotenciaDoCanalEmail() {
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
        postRequestedFor(urlPathEqualTo("/v1/emails"))
            .withHeader(
                "Idempotency-Key",
                equalTo(
                    "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222:email")));
  }
}
