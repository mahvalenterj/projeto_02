package br.gov.saude.upa.infrastructure.canais;

import br.gov.saude.upa.application.ChaveDeIdempotencia;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.domain.MensagemDeAviso;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Camada anticorrupção de um canal HTTP: traduz o contrato do provedor para {@link
 * ResultadoDoEnvio} e aplica a resiliência (Resilience4j programático, sem anotações).
 *
 * <p>Ordem, de fora para dentro: retry, circuit breaker, rate limiter, bulkhead, chamada HTTP.
 * Token, destino e texto nunca vão para log, exceção nem {@code toString}.
 */
final class ClienteDeCanal {

  private static final Logger log = LoggerFactory.getLogger(ClienteDeCanal.class);
  private static final int ACEITO = 202;
  private static final Pattern SEGUNDOS = Pattern.compile("\\d+");
  private static final int DIGITOS_MAXIMOS_DE_SEGUNDOS = 9;

  /** Data HTTP ou número gigante: tratado como espera acima de qualquer teto configurável. */
  private static final Duration ACIMA_DE_QUALQUER_TETO = Duration.ofDays(36_500);

  private static final int LIMITE_DO_CORPO = 8 * 1024;
  private static final ExecutorService LEITURA = Executors.newVirtualThreadPerTaskExecutor();
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .build();

  /** Resposta de sucesso do contrato: {@code {"id", "situacao"}}. */
  private record RespostaDoProvedor(String id, String situacao) {}

  private final String nome;
  private final String caminho;
  private final String situacaoEsperada;
  private final Duration retryAfterMaximo;
  private final Duration prazoDoCorpo;
  private final IntervalFunction backoff;
  private final RestClient http;
  private final Retry retry;
  private final CircuitBreaker breaker;
  private final RateLimiter limitador;
  private final Bulkhead bulkhead;

  ClienteDeCanal(
      String nome, CanaisProperties.Canal canal, String caminho, String situacaoEsperada) {
    this(nome, canal, caminho, situacaoEsperada, criarHttp(canal));
  }

  /** Permite trocar o cliente HTTP (os testes forçam falhas inesperadas por aqui). */
  ClienteDeCanal(
      String nome,
      CanaisProperties.Canal canal,
      String caminho,
      String situacaoEsperada,
      RestClient http) {
    this.nome = nome;
    this.caminho = caminho;
    this.situacaoEsperada = situacaoEsperada;
    this.retryAfterMaximo = canal.retry().retryAfterMaximo();
    this.prazoDoCorpo = canal.timeoutLeitura();
    this.backoff =
        IntervalFunction.ofExponentialRandomBackoff(
            canal.retry().esperaInicial(), canal.retry().multiplicador(), canal.retry().jitter());
    this.http = http;
    this.retry = criarRetry(nome, canal.retry());
    this.breaker = criarBreaker(nome, canal.breaker());
    this.limitador = criarLimitador(nome, canal.limitador());
    this.bulkhead = criarBulkhead(nome, canal.bulkhead());
  }

  /** Falha rápido, sem citar o valor, quando o destino ou a mensagem não servem. */
  static void validar(String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave) {
    Objects.requireNonNull(destino, "destino");
    Objects.requireNonNull(mensagem, "mensagem");
    Objects.requireNonNull(chave, "chave");
    if (destino.isBlank()) {
      throw new IllegalArgumentException("destino não pode ser vazio");
    }
  }

  /** Envia o pedido; só devolve {@code Confirmado} com o aceite explícito do provedor. */
  ResultadoDoEnvio enviar(Object pedido, ChaveDeIdempotencia chave) {
    // Calculada uma vez, fora do Supplier: toda repetição do Retry leva o mesmo valor.
    String chaveDoProvedor = chave.eventoId() + ":" + chave.agendamentoId() + ":" + nome;
    try {
      return Decorators.ofSupplier(() -> tentar(pedido, chaveDoProvedor))
          .withBulkhead(bulkhead)
          .withRateLimiter(limitador)
          .withCircuitBreaker(breaker)
          .withRetry(retry)
          .decorate()
          .get();
    } catch (FalhaDoCanal e) {
      return switch (e.tipo()) {
        case INDETERMINADA -> {
          // A mensagem pode ter sido aceita: sem retry, sem próximo canal, e conta no breaker.
          log.warn("Canal {} indeterminado: {}", nome, e.getMessage());
          yield new ResultadoDoEnvio.Indeterminado();
        }
        case TRANSITORIA, SEM_RETRY, DO_PEDIDO -> {
          log.warn("Canal {} esgotado: {}", nome, e.getMessage());
          yield new ResultadoDoEnvio.Esgotado();
        }
      };
    } catch (CallNotPermittedException e) {
      log.warn("Canal {} com circuit breaker aberto: envio pulado", nome);
      return new ResultadoDoEnvio.Esgotado();
    } catch (RequestNotPermitted e) {
      log.warn("Canal {} sem vaga no limitador de taxa: envio negado", nome);
      return new ResultadoDoEnvio.Esgotado();
    } catch (BulkheadFullException e) {
      log.warn("Canal {} com bulkhead cheio: envio negado", nome);
      return new ResultadoDoEnvio.Esgotado();
    } catch (RuntimeException e) {
      // Falha inesperada: nunca vira sucesso nem some. Só o tipo vai para o log (a mensagem
      // poderia carregar destino ou texto); o chamador segue o fallback e acaba em revisão manual.
      log.error("Canal {} com falha inesperada: tipo={}", nome, e.getClass().getName());
      return new ResultadoDoEnvio.Esgotado();
    }
  }

  /** Espera usada pelo Retry depois da falha: a do provedor ({@code Retry-After}) ou o backoff. */
  long esperaDoRetry(int numeroDaFalha, Throwable falha) {
    if (falha instanceof FalhaDoCanal canal && canal.esperaSugerida().isPresent()) {
      return canal.esperaSugerida().get().toMillis();
    }
    return esperaAntesDaRepeticao(numeroDaFalha);
  }

  /** Espera, em ms, antes da repetição que segue a falha de número {@code numeroDaFalha}. */
  long esperaAntesDaRepeticao(int numeroDaFalha) {
    return backoff.apply(numeroDaFalha);
  }

  private ResultadoDoEnvio tentar(Object pedido, String chaveDoProvedor) {
    try {
      return http.post()
          .uri(caminho)
          .header("Idempotency-Key", chaveDoProvedor)
          .contentType(MediaType.APPLICATION_JSON)
          .body(pedido)
          .exchange((requisicao, resposta) -> classificar(resposta));
    } catch (ResourceAccessException e) {
      // Timeout, conexão recusada ou derrubada. Só o tipo da causa vai adiante (a mensagem
      // do JDK pode citar o endereço).
      String causa =
          e.getCause() == null ? "desconhecida" : e.getCause().getClass().getSimpleName();
      throw FalhaDoCanal.transitoria("falha de comunicação (" + causa + ")", null);
    }
  }

  private ResultadoDoEnvio classificar(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse resposta) throws IOException {
    HttpStatusCode codigo = resposta.getStatusCode();
    int status = codigo.value();
    if (codigo.is2xxSuccessful()) {
      return lerConfirmacao(resposta, status);
    }
    if (status == 429) {
      throw excessoDeRequisicoes(resposta.getHeaders());
    }
    if (codigo.is5xxServerError()) {
      throw FalhaDoCanal.transitoria("erro do provedor (status " + status + ")", null);
    }
    if (status == 401 || status == 403) {
      throw FalhaDoCanal.semRetry("credencial recusada (status " + status + ")");
    }
    if (codigo.is4xxClientError()) {
      throw FalhaDoCanal.doPedido("pedido recusado (status " + status + ")");
    }
    throw foraDoContrato(status);
  }

  /** Devolve {@code Confirmado} ou lança a falha indeterminada (que conta no breaker). */
  private ResultadoDoEnvio lerConfirmacao(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse resposta, int status) {
    if (status != ACEITO) {
      throw foraDoContrato(status);
    }
    Optional<byte[]> bytes = lerCorpoComPrazo(resposta);
    if (bytes.isEmpty()) {
      throw foraDoContrato(status);
    }
    try {
      RespostaDoProvedor corpo = JSON.readValue(bytes.get(), RespostaDoProvedor.class);
      if (corpo != null
          && corpo.id() != null
          && !corpo.id().isBlank()
          && situacaoEsperada.equals(corpo.situacao())) {
        return new ResultadoDoEnvio.Confirmado();
      }
    } catch (IOException e) {
      // Corpo ilegível ou truncado: a mensagem pode ter sido aceita, então fica indeterminado.
      throw foraDoContrato(status);
    }
    throw foraDoContrato(status);
  }

  /**
   * Lê o corpo do aceite com prazo total (o mesmo timeout de leitura do canal) e limite de tamanho.
   * O timeout do cliente HTTP só vale até os cabeçalhos: sem este prazo, um provedor que manda o
   * 202 e trava o corpo seguraria a vaga do bulkhead. Vazio = estourou o prazo, o limite, ou a
   * leitura falhou; o 202 já chegou, então o chamador devolve {@code Indeterminado}.
   */
  private Optional<byte[]> lerCorpoComPrazo(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse resposta) {
    Future<byte[]> leitura =
        LEITURA.submit(
            () -> {
              try (InputStream corpo = resposta.getBody()) {
                return corpo.readNBytes(LIMITE_DO_CORPO + 1);
              }
            });
    try {
      byte[] bytes = leitura.get(prazoDoCorpo.toMillis(), TimeUnit.MILLISECONDS);
      return bytes.length > LIMITE_DO_CORPO ? Optional.empty() : Optional.of(bytes);
    } catch (TimeoutException e) {
      leitura.cancel(true);
      resposta.close();
      return Optional.empty();
    } catch (ExecutionException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      leitura.cancel(true);
      resposta.close();
      return Optional.empty();
    }
  }

  private static FalhaDoCanal foraDoContrato(int status) {
    return FalhaDoCanal.indeterminada("resposta fora do contrato (status " + status + ")");
  }

  private FalhaDoCanal excessoDeRequisicoes(HttpHeaders cabecalhos) {
    Optional<Duration> espera = lerRetryAfter(cabecalhos.getFirst(HttpHeaders.RETRY_AFTER));
    if (espera.isEmpty()) {
      return FalhaDoCanal.transitoria("limite de taxa do provedor (status 429)", null);
    }
    if (espera.get().compareTo(retryAfterMaximo) > 0) {
      return FalhaDoCanal.semRetry("limite de taxa do provedor com espera acima do teto (429)");
    }
    return FalhaDoCanal.transitoria("limite de taxa do provedor (status 429)", espera.get());
  }

  /**
   * Segundos inteiros valem como são. Data HTTP e números de 10+ dígitos contam como espera acima
   * do teto (o provedor pede uma pausa longa; repetir em 0,5 s a desrespeitaria). Negativo, vazio
   * ou texto livre contam como ausente e caem no backoff.
   */
  private static Optional<Duration> lerRetryAfter(String valor) {
    if (valor == null) {
      return Optional.empty();
    }
    String limpo = valor.strip();
    if (SEGUNDOS.matcher(limpo).matches()) {
      return Optional.of(
          limpo.length() > DIGITOS_MAXIMOS_DE_SEGUNDOS
              ? ACIMA_DE_QUALQUER_TETO
              : Duration.ofSeconds(Long.parseLong(limpo)));
    }
    try {
      DateTimeFormatter.RFC_1123_DATE_TIME.parse(limpo);
      return Optional.of(ACIMA_DE_QUALQUER_TETO);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static RestClient criarHttp(CanaisProperties.Canal canal) {
    HttpClient cliente =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(canal.timeoutConexao())
            .build();
    JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(cliente);
    fabrica.setReadTimeout(canal.timeoutLeitura());
    return RestClient.builder()
        .baseUrl(canal.baseUrl())
        .requestFactory(fabrica)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + canal.token())
        .build();
  }

  private Retry criarRetry(String nome, CanaisProperties.PoliticaDeRetry politica) {
    RetryConfig configuracao =
        RetryConfig.<ResultadoDoEnvio>custom()
            .maxAttempts(politica.tentativas())
            .retryOnException(ClienteDeCanal::valeRepetir)
            .intervalBiFunction(
                (tentativa, resultado) ->
                    esperaDoRetry(tentativa, resultado.isLeft() ? resultado.getLeft() : null))
            .build();
    return Retry.of(nome, configuracao);
  }

  private static boolean valeRepetir(Throwable erro) {
    return erro instanceof FalhaDoCanal falha && falha.tipo() == FalhaDoCanal.Tipo.TRANSITORIA;
  }

  private static CircuitBreaker criarBreaker(
      String nome, CanaisProperties.PoliticaDeBreaker politica) {
    CircuitBreakerConfig configuracao =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(politica.janela())
            .minimumNumberOfCalls(politica.minimoDeChamadas())
            .failureRateThreshold(politica.taxaDeFalha())
            .waitDurationInOpenState(politica.tempoAberto())
            .recordException(ClienteDeCanal::contaNoBreaker)
            .ignoreException(ClienteDeCanal::naoContaNoBreaker)
            .build();
    return CircuitBreaker.of(nome, configuracao);
  }

  private static boolean contaNoBreaker(Throwable erro) {
    if (!(erro instanceof FalhaDoCanal falha)) {
      // Exceção inesperada nunca conta como sucesso (as negações locais saem antes, pelo ignore).
      return true;
    }
    return switch (falha.tipo()) {
      case TRANSITORIA, SEM_RETRY, INDETERMINADA -> true;
      case DO_PEDIDO -> false;
    };
  }

  /** Erro de um paciente só e negação local (limitador, bulkhead) não dizem nada do provedor. */
  private static boolean naoContaNoBreaker(Throwable erro) {
    if (erro instanceof RequestNotPermitted || erro instanceof BulkheadFullException) {
      return true;
    }
    return erro instanceof FalhaDoCanal falha && falha.tipo() == FalhaDoCanal.Tipo.DO_PEDIDO;
  }

  private static RateLimiter criarLimitador(
      String nome, CanaisProperties.PoliticaDeLimitador politica) {
    return RateLimiter.of(
        nome,
        RateLimiterConfig.custom()
            .limitForPeriod(politica.limite())
            .limitRefreshPeriod(politica.periodo())
            .timeoutDuration(politica.espera())
            .build());
  }

  private static Bulkhead criarBulkhead(String nome, CanaisProperties.PoliticaDeBulkhead politica) {
    return Bulkhead.of(
        nome,
        BulkheadConfig.custom()
            .maxConcurrentCalls(politica.maximo())
            .maxWaitDuration(politica.espera())
            .build());
  }
}
