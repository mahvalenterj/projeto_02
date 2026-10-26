package br.gov.saude.upa.infrastructure.canais;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.gov.saude.upa.application.ChaveDeIdempotencia;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.domain.MensagemDeAviso;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Contrato HTTP e política de resiliência, iguais nos dois canais. Cada canal herda esta classe e
 * informa só o que muda (caminho, situação de sucesso e corpo do pedido). Cobre o CA11 e apoia o
 * CA5 e o CA18 no nível do adapter.
 */
abstract class NotificadorHttpContratoTest {

  @RegisterExtension
  static WireMockExtension provedor =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().extensions(new ChegadaDeRequisicao()))
          .build();

  /** Avisa no instante em que a requisição chega e casa com o stub, antes de qualquer atraso. */
  static final class ChegadaDeRequisicao implements ServeEventListener {
    static final AtomicReference<CountDownLatch> CHEGOU =
        new AtomicReference<>(new CountDownLatch(1));
    static final AtomicInteger CHEGADAS = new AtomicInteger();
    static final List<String> CHAVES = new CopyOnWriteArrayList<>();

    @Override
    public String getName() {
      return "chegada-de-requisicao";
    }

    @Override
    public void afterMatch(ServeEvent serveEvent, Parameters parameters) {
      CHEGADAS.incrementAndGet();
      CHAVES.add(serveEvent.getRequest().getHeader("Idempotency-Key"));
      CHEGOU.get().countDown();
    }
  }

  static final MensagemDeAviso MENSAGEM =
      new MensagemDeAviso("UPA Central: o tomógrafo está indisponível hoje às 10:30 (texto único)");

  static final ChaveDeIdempotencia CHAVE =
      new ChaveDeIdempotencia(
          UUID.fromString("11111111-1111-1111-1111-111111111111"),
          UUID.fromString("22222222-2222-2222-2222-222222222222"));

  private ListAppender<ILoggingEvent> logs;
  private Logger loggerDosCanais;

  /** Nome do canal no sufixo da chave de idempotência. */
  protected abstract String nomeDoCanal();

  protected abstract String caminho();

  protected abstract String situacaoDeSucesso();

  protected abstract String destino();

  /** Corpo JSON que o canal deve enviar ao provedor. */
  protected abstract String corpoEsperado(String texto);

  /** Cria o adapter do canal; o breaker e os limites vivem no adapter, então reuse a instância. */
  protected abstract Enviador novoEnviador(CanaisProperties.Canal canal);

  private ResultadoDoEnvio enviar(CanaisProperties.Canal canal, String destino) {
    return novoEnviador(canal).enviar(destino, MENSAGEM, CHAVE);
  }

  @BeforeEach
  void capturarLogs() {
    ChegadaDeRequisicao.CHEGADAS.set(0);
    ChegadaDeRequisicao.CHAVES.clear();
    loggerDosCanais = (Logger) LoggerFactory.getLogger("br.gov.saude.upa.infrastructure.canais");
    logs = new ListAppender<>();
    logs.start();
    loggerDosCanais.addAppender(logs);
  }

  @AfterEach
  void soltarLogs() {
    loggerDosCanais.detachAppender(logs);
  }

  protected CanalDeTeste canal() {
    return CanalDeTeste.para(provedor.baseUrl());
  }

  private String corpoValido() {
    return "{\"id\":\"msg-1\",\"situacao\":\"" + situacaoDeSucesso() + "\"}";
  }

  private void responder(int status, String corpo) {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpo)));
  }

  private void responderSucesso() {
    responder(202, corpoValido());
  }

  private void verificarRequisicoes(int quantidade) {
    provedor.verify(quantidade, postRequestedFor(urlPathEqualTo(caminho())));
  }

  private ResultadoDoEnvio enviarComPadrao() {
    return enviar(canal().build(), destino());
  }

  @Test
  void deveConfirmarEEnviarTokenECorpoDoContratoQuandoProvedorResponder202() {
    responderSucesso();

    ResultadoDoEnvio resultado = enviarComPadrao();

    assertThat(resultado).isEqualTo(new ResultadoDoEnvio.Confirmado());
    provedor.verify(
        1,
        postRequestedFor(urlPathEqualTo(caminho()))
            .withHeader("Authorization", equalTo("Bearer " + CanalDeTeste.TOKEN))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(equalToJson(corpoEsperado(MENSAGEM.texto()))));
  }

  @Test
  void deveDevolverIndeterminadoSemRepetirQuandoCorpoForIlegivel() {
    responder(202, "isto não é json");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "[]",
        "{}",
        "{\"id\":\"msg-1\"}",
        "{\"situacao\":\"ACEITA\"}",
        "{\"id\":\"\",\"situacao\":\"ACEITA\"}",
        "{\"id\":\"msg-1\",\"situacao\":\"REJEITADA\"}",
        "{\"id\":1,\"situacao\":[]}"
      })
  void deveDevolverIndeterminadoSemRepetirQuandoRespostaForaDoContrato(String corpo) {
    responder(202, corpo);

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 204})
  void deveDevolverIndeterminadoSemRepetirQuando2xxNaoForOCodigoDoContrato(int status) {
    responder(status, corpoValido());

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    verificarRequisicoes(1);
  }

  @Test
  void deveDevolverIndeterminadoSemRepetirQuandoProvedorRedirecionar() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/outro")));

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void deveRepetirAteEsgotarAsTentativasQuandoProvedorResponder5xx(int status) {
    responder(status, "{}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(3);
  }

  @Test
  void deveConfirmarNaSegundaTentativaQuandoPrimeiraResponder500() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("falha-e-sucesso")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("recuperado"));
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("falha-e-sucesso")
            .whenScenarioStateIs("recuperado")
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())));

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Confirmado());
    verificarRequisicoes(2);
  }

  @Test
  void deveRepetirQuandoRespostaDemorarMaisQueOTimeoutDeLeitura() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(aResponse().withStatus(202).withBody(corpoValido()).withFixedDelay(3000)));
    CanaisProperties.Canal canal = canal().timeoutLeitura(Duration.ofSeconds(1)).build();

    assertThat(enviar(canal, destino())).isEqualTo(new ResultadoDoEnvio.Esgotado());
    // Conta as chegadas pelo listener (o diário do WireMock só registra respostas concluídas).
    // Folga de 1 s no timeout: se o cliente desiste antes de o Jetty ler a requisição, ela não
    // casa.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(ChegadaDeRequisicao.CHEGADAS.get()).isEqualTo(3));
  }

  @ParameterizedTest
  @EnumSource(
      value = Fault.class,
      names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
  void deveRepetirQuandoConexaoForDerrubada(Fault falha) {
    provedor.stubFor(post(urlPathEqualTo(caminho())).willReturn(aResponse().withFault(falha)));

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(3);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void deveDesistirSemRepetirQuandoCredencialForRecusada(int status) {
    responder(status, "{}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 409, 422})
  void naoDeveFazerRetryQuandoProvedorResponder4xxDeValidacao(int status) {
    responder(status, "{}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(1);
  }

  @Test
  void deveRespeitarRetryAfterQuandoFor429ComIntervaloAbaixoDoTeto() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("limite")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
            .willSetStateTo("liberado"));
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("limite")
            .whenScenarioStateIs("liberado")
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())));
    CanaisProperties.Canal canal = canal().retryAfterMaximo(Duration.ofSeconds(3)).build();

    long inicio = System.nanoTime();
    ResultadoDoEnvio resultado = enviar(canal, destino());
    Duration decorrido = Duration.ofNanos(System.nanoTime() - inicio);

    assertThat(resultado).isEqualTo(new ResultadoDoEnvio.Confirmado());
    verificarRequisicoes(2);
    // O backoff de teste é de 10 ms: só o Retry-After explica uma espera de um segundo.
    assertThat(decorrido).isGreaterThanOrEqualTo(Duration.ofMillis(990));
  }

  @Test
  void deveDesistirSemRepetirQuandoRetryAfterDoRateLimitExcederOTeto() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));
    CanaisProperties.Canal canal = canal().retryAfterMaximo(Duration.ofMillis(500)).build();

    assertThat(enviar(canal, destino())).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "-1", "1.5", "1e3", ""})
  void deveRepetirComBackoffQuandoRetryAfterDo429ForInvalido(String retryAfter) {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("limite-invalido")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", retryAfter))
            .willSetStateTo("liberado"));
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .inScenario("limite-invalido")
            .whenScenarioStateIs("liberado")
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())));

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Confirmado());
    verificarRequisicoes(2);
  }

  @Test
  void deveRepetirComBackoffQuandoFor429SemRetryAfter() {
    responder(429, "{}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(3);
  }

  @Test
  void deveEsperarOBackoffExponencialEntreAsTentativas() {
    responder(500, "{}");
    // Esperas mínimas com jitter de 50%: 200 * 0,5 + 400 * 0,5 = 300 ms.
    CanaisProperties.Canal canal = canal().esperaInicial(Duration.ofMillis(200)).build();

    long inicio = System.nanoTime();
    enviar(canal, destino());
    Duration decorrido = Duration.ofNanos(System.nanoTime() - inicio);

    verificarRequisicoes(3);
    assertThat(decorrido).isGreaterThanOrEqualTo(Duration.ofMillis(290));
    // Cota superior folgada (máquina lenta): as esperas ficam abaixo de 900 ms, longe dos 5 s.
    assertThat(decorrido).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void deveDevolverIndeterminadoSemRepetirELiberarAVagaQuandoOCorpoDoAceiteNaoTerminarNoPrazo() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())
                    .withChunkedDribbleDelay(10, 8000)));
    Enviador enviador =
        novoEnviador(
            canal().timeoutLeitura(Duration.ofMillis(1500)).bulkhead(1, Duration.ZERO).build());

    long inicio = System.nanoTime();
    ResultadoDoEnvio resultado = enviador.enviar(destino(), MENSAGEM, CHAVE);
    Duration decorrido = Duration.ofNanos(System.nanoTime() - inicio);

    assertThat(resultado).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    // O prazo vale para o corpo inteiro: sem ele a leitura esperaria os 8 s do provedor.
    assertThat(decorrido).isLessThan(Duration.ofSeconds(6));
    // A vaga do bulkhead (1) foi devolvida: o próximo envio passa.
    responderSucesso();
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Confirmado());
    // Duas chegadas no total: o aceite que travou (sem retry) e o envio seguinte.
    assertThat(ChegadaDeRequisicao.CHEGADAS.get()).isEqualTo(2);
  }

  @Test
  void deveDevolverIndeterminadoSemRepetirQuandoOCorpoDoAceiteExcederOLimiteDeTamanho() {
    String lixo = "a".repeat(20_000);
    responder(
        202,
        "{\"id\":\"msg-1\",\"situacao\":\"" + situacaoDeSucesso() + "\",\"x\":\"" + lixo + "\"}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Indeterminado());
    verificarRequisicoes(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Wed, 21 Oct 2026 07:28:00 GMT", "12345678901", "99999999999999999999999999"})
  void deveDesistirSemRepetirQuandoRetryAfterForDataHttpOuNumeroGigante(String retryAfter) {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", retryAfter)));

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    verificarRequisicoes(1);
  }

  @Test
  void deveAbrirOBreakerDepoisDaJanelaEPularOProvedor() {
    responder(500, "{}");
    Enviador enviador = novoEnviador(canal().tentativas(1).breaker(4).build());

    for (int i = 0; i < 6; i++) {
      assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
          .isEqualTo(new ResultadoDoEnvio.Esgotado());
    }

    // As 4 primeiras chamadas abriram o breaker; a quinta e a sexta nem chegam ao provedor.
    verificarRequisicoes(4);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"200", "201", "204", "ilegivel", "situacao-errada", "redirecionamento", "grande"})
  void deveContarIndeterminadoNoBreakerSemTrocarOResultadoEnquantoEleEstiverFechado(
      String cenario) {
    prepararRespostaForaDoContrato(cenario);
    Enviador enviador = novoEnviador(canal().tentativas(1).breaker(2).build());

    // Fechado: continua Indeterminado (nunca Confirmado) e sem retry.
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Indeterminado());
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Indeterminado());
    // Aberto pela janela de 2 falhas: nem chega ao provedor.
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Esgotado());

    verificarRequisicoes(2);
  }

  @Test
  void deveContarNoBreakerOIndeterminadoPorEstouroDoPrazoDoCorpo() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())
                    .withChunkedDribbleDelay(10, 8000)));
    Enviador enviador =
        novoEnviador(
            canal().tentativas(1).timeoutLeitura(Duration.ofMillis(1500)).breaker(2).build());

    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Indeterminado());
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Indeterminado());
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Esgotado());

    // Só duas chegadas: a terceira chamada foi barrada pelo breaker.
    assertThat(ChegadaDeRequisicao.CHEGADAS.get()).isEqualTo(2);
  }

  private void prepararRespostaForaDoContrato(String cenario) {
    switch (cenario) {
      case "200", "201", "204" -> responder(Integer.parseInt(cenario), corpoValido());
      case "ilegivel" -> responder(202, "isto não é json");
      case "situacao-errada" -> responder(202, "{\"id\":\"msg-1\",\"situacao\":\"REJEITADA\"}");
      case "redirecionamento" ->
          provedor.stubFor(
              post(urlPathEqualTo(caminho()))
                  .willReturn(aResponse().withStatus(302).withHeader("Location", "/outro")));
      case "grande" ->
          responder(
              202, "{\"id\":\"msg-1\",\"situacao\":\"X\",\"x\":\"" + "a".repeat(20_000) + "\"}");
      default -> throw new IllegalArgumentException(cenario);
    }
  }

  @Test
  void deveContar401NoBreakerPoisAfetaTodosOsPacientes() {
    responder(401, "{}");
    Enviador enviador = novoEnviador(canal().tentativas(1).breaker(4).build());

    for (int i = 0; i < 6; i++) {
      assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
          .isEqualTo(new ResultadoDoEnvio.Esgotado());
    }

    verificarRequisicoes(4);
  }

  @Test
  void naoDeveContarErroDeValidacaoNoBreakerPoisAfetaUmPacienteSo() {
    responder(400, "{}");
    Enviador enviador = novoEnviador(canal().tentativas(1).breaker(4).build());

    for (int i = 0; i < 6; i++) {
      enviador.enviar(destino(), MENSAGEM, CHAVE);
    }
    responderSucesso();

    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Confirmado());
    verificarRequisicoes(7);
  }

  @Test
  void deveNegarSemChamarOProvedorQuandoOLimitadorEsgotar() {
    responderSucesso();
    Enviador enviador =
        novoEnviador(canal().limitador(2, Duration.ofHours(1), Duration.ZERO).build());

    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Confirmado());
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Confirmado());
    assertThat(enviador.enviar(destino(), MENSAGEM, CHAVE))
        .isEqualTo(new ResultadoDoEnvio.Esgotado());

    verificarRequisicoes(2);
  }

  @Test
  void deveNegarSemChamarOProvedorQuandoOBulkheadEstiverCheio() throws Exception {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpoValido())
                    .withFixedDelay(3000)));
    Enviador enviador =
        novoEnviador(
            canal().timeoutLeitura(Duration.ofSeconds(5)).bulkhead(1, Duration.ZERO).build());

    CountDownLatch chegou = new CountDownLatch(1);
    ChegadaDeRequisicao.CHEGOU.set(chegou);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<ResultadoDoEnvio> primeiro =
          CompletableFuture.supplyAsync(
              () -> enviador.enviar(destino(), MENSAGEM, CHAVE), executor);
      // Determinístico: o listener do WireMock dispara quando a requisição chega, antes do atraso.
      assertThat(chegou.await(10, TimeUnit.SECONDS)).isTrue();

      ResultadoDoEnvio segundo = enviador.enviar(destino(), MENSAGEM, CHAVE);

      assertThat(segundo).isEqualTo(new ResultadoDoEnvio.Esgotado());
      assertThat(primeiro.get()).isEqualTo(new ResultadoDoEnvio.Confirmado());
    }
    verificarRequisicoes(1);
  }

  @Test
  void naoDeveRegistrarTokenDestinoNemTextoEmLogEmNenhumDesfecho() {
    // Esgotado por 5xx, esgotado por 401, indeterminado por corpo ilegível e por redirecionamento.
    responder(500, "{}");
    enviarComPadrao();
    responder(401, "{}");
    enviarComPadrao();
    responder(202, "texto ilegível");
    enviarComPadrao();
    provedor.stubFor(post(urlPathEqualTo(caminho())).willReturn(aResponse().withStatus(302)));
    enviarComPadrao();

    List<ILoggingEvent> eventos = logs.list;
    assertThat(eventos).isNotEmpty();
    for (ILoggingEvent evento : eventos) {
      String conteudo = evento.getFormattedMessage() + " " + evento.getThrowableProxy();
      assertThat(conteudo)
          .doesNotContain(CanalDeTeste.TOKEN)
          .doesNotContain(destino())
          .doesNotContain(MENSAGEM.texto())
          .doesNotContain("texto ilegível")
          .doesNotContain("Bearer");
    }
  }

  @Test
  void deveRecusarDestinoOuMensagemInvalidosSemCitarOValor() {
    Enviador enviador = novoEnviador(canal().build());

    assertThatThrownBy(() -> enviador.enviar(null, MENSAGEM, CHAVE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> enviador.enviar("   ", MENSAGEM, CHAVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> enviador.enviar(destino(), null, CHAVE))
        .isInstanceOf(NullPointerException.class);
    verificarRequisicoes(0);
  }

  private String chaveEsperada() {
    return CHAVE.eventoId() + ":" + CHAVE.agendamentoId() + ":" + nomeDoCanal();
  }

  @Test
  void deveEnviarIdempotencyKeyComEventoAgendamentoECanal() {
    responderSucesso();

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Confirmado());
    provedor.verify(
        1,
        postRequestedFor(urlPathEqualTo(caminho()))
            .withHeader("Idempotency-Key", equalTo(chaveEsperada())));
  }

  @Test
  void deveRepetirAMesmaChaveEmTodasAsTentativasDeUm5xx() {
    responder(500, "{}");

    assertThat(enviarComPadrao()).isEqualTo(new ResultadoDoEnvio.Esgotado());
    provedor.verify(
        3,
        postRequestedFor(urlPathEqualTo(caminho()))
            .withHeader("Idempotency-Key", equalTo(chaveEsperada())));
  }

  @Test
  void deveRepetirAMesmaChaveDepoisDeTimeoutDeLeitura() {
    provedor.stubFor(
        post(urlPathEqualTo(caminho()))
            .willReturn(aResponse().withStatus(202).withBody(corpoValido()).withFixedDelay(3000)));
    CanaisProperties.Canal canal = canal().timeoutLeitura(Duration.ofSeconds(1)).build();

    assertThat(enviar(canal, destino())).isEqualTo(new ResultadoDoEnvio.Esgotado());
    // Folga de 1 s no timeout (e não 200 ms): o Jetty precisa ler a requisição antes de o cliente
    // desistir, senão ela nunca casa com o stub e a chegada não é registrada (causa provada).
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(ChegadaDeRequisicao.CHAVES).hasSizeGreaterThanOrEqualTo(2));
    assertThat(ChegadaDeRequisicao.CHAVES).containsOnly(chaveEsperada());
  }

  @Test
  void deveRecusarChaveNulaSemChamarOProvedor() {
    Enviador enviador = novoEnviador(canal().build());

    assertThatThrownBy(() -> enviador.enviar(destino(), MENSAGEM, null))
        .isInstanceOf(NullPointerException.class);
    verificarRequisicoes(0);
  }

  @Test
  void naoDeveColocarDestinoTextoNemTokenNoCabecalhoDeIdempotencia() {
    responderSucesso();

    enviarComPadrao();

    assertThat(ChegadaDeRequisicao.CHAVES).hasSize(1);
    assertThat(ChegadaDeRequisicao.CHAVES.get(0))
        .isEqualTo(chaveEsperada())
        .doesNotContain(destino())
        .doesNotContain(MENSAGEM.texto())
        .doesNotContain(CanalDeTeste.TOKEN);
  }

  /** O que os dois adapters têm em comum: a assinatura das portas. */
  @FunctionalInterface
  protected interface Enviador {
    ResultadoDoEnvio enviar(String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave);
  }
}
