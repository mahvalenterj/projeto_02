package br.gov.saude.upa.infrastructure.canais;

import java.time.Duration;

/**
 * Monta a configuração de um canal para os testes: esperas de milissegundos e disjuntor que não
 * abre, salvo quando o teste pede o contrário.
 */
final class CanalDeTeste {

  static final String TOKEN = "token-secreto-123";

  private String baseUrl;
  // Folgados de propósito: a máquina de build é lenta e um timeout espúrio mudaria o resultado.
  private Duration timeoutConexao = Duration.ofSeconds(5);
  private Duration timeoutLeitura = Duration.ofSeconds(5);
  private int tentativas = 3;
  private Duration esperaInicial = Duration.ofMillis(10);
  private double jitter = 0.5;
  private Duration retryAfterMaximo = Duration.ofSeconds(5);
  private int janela = 1000;
  private int minimoDeChamadas = 1000;
  private Duration tempoAberto = Duration.ofSeconds(30);
  private int limite = 1000;
  private Duration periodo = Duration.ofSeconds(1);
  private Duration esperaDoLimitador = Duration.ofSeconds(1);
  private int bulkhead = 50;
  private Duration esperaDoBulkhead = Duration.ofSeconds(1);

  private CanalDeTeste(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  static CanalDeTeste para(String baseUrl) {
    return new CanalDeTeste(baseUrl);
  }

  CanalDeTeste timeoutLeitura(Duration valor) {
    this.timeoutLeitura = valor;
    return this;
  }

  CanalDeTeste tentativas(int valor) {
    this.tentativas = valor;
    return this;
  }

  CanalDeTeste esperaInicial(Duration valor) {
    this.esperaInicial = valor;
    return this;
  }

  CanalDeTeste retryAfterMaximo(Duration valor) {
    this.retryAfterMaximo = valor;
    return this;
  }

  CanalDeTeste breaker(int janelaEMinimo) {
    this.janela = janelaEMinimo;
    this.minimoDeChamadas = janelaEMinimo;
    return this;
  }

  CanalDeTeste limitador(int limitePorPeriodo, Duration periodoDoLimite, Duration espera) {
    this.limite = limitePorPeriodo;
    this.periodo = periodoDoLimite;
    this.esperaDoLimitador = espera;
    return this;
  }

  CanalDeTeste bulkhead(int maximo, Duration espera) {
    this.bulkhead = maximo;
    this.esperaDoBulkhead = espera;
    return this;
  }

  CanaisProperties.Canal build() {
    return new CanaisProperties.Canal(
        baseUrl,
        TOKEN,
        timeoutConexao,
        timeoutLeitura,
        new CanaisProperties.PoliticaDeRetry(
            tentativas, esperaInicial, 2.0, jitter, retryAfterMaximo),
        new CanaisProperties.PoliticaDeBreaker(janela, minimoDeChamadas, 50, tempoAberto),
        new CanaisProperties.PoliticaDeLimitador(limite, periodo, esperaDoLimitador),
        new CanaisProperties.PoliticaDeBulkhead(bulkhead, esperaDoBulkhead));
  }
}
