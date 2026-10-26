package br.gov.saude.upa.infrastructure.canais;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.application.ResultadoDoEnvio;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

class ClienteDeCanalTest {

  private static ClienteDeCanal cliente() {
    CanaisProperties.Canal canal =
        CanalDeTeste.para("http://localhost:1").esperaInicial(Duration.ofMillis(1000)).build();
    return new ClienteDeCanal("teste", canal, "/v1/x", "OK");
  }

  @Test
  void deveDobrarAEsperaAcadaFalhaDentroDoJitter() {
    ClienteDeCanal cliente = cliente();

    for (int i = 0; i < 200; i++) {
      assertThat(cliente.esperaAntesDaRepeticao(1)).isBetween(500L, 1500L);
      assertThat(cliente.esperaAntesDaRepeticao(2)).isBetween(1000L, 3000L);
      assertThat(cliente.esperaAntesDaRepeticao(3)).isBetween(2000L, 6000L);
    }
  }

  @Test
  void deveEspalharAsEsperasComJitterParaNaoSincronizarOsClientes() {
    ClienteDeCanal cliente = cliente();
    Set<Long> esperas = new HashSet<>();

    for (int i = 0; i < 50; i++) {
      esperas.add(cliente.esperaAntesDaRepeticao(1));
    }

    assertThat(esperas.size()).isGreaterThan(10);
  }

  @Test
  void deveEsperarOBackoffExponencialOuORetryAfterDoProvedorEntreAsTentativas() {
    ClienteDeCanal cliente = cliente();
    Throwable semEspera = FalhaDoCanal.transitoria("x", null);
    Throwable comEspera = FalhaDoCanal.transitoria("x", Duration.ofSeconds(3));

    for (int i = 0; i < 100; i++) {
      // Esperas usadas pelo Retry: 1 s (±50%) depois da 1ª falha e 2 s (±50%) depois da 2ª.
      assertThat(cliente.esperaDoRetry(1, semEspera)).isBetween(500L, 1500L);
      assertThat(cliente.esperaDoRetry(2, semEspera)).isBetween(1000L, 3000L);
    }
    assertThat(cliente.esperaDoRetry(1, comEspera)).isEqualTo(3000L);
    assertThat(cliente.esperaDoRetry(1, new IllegalStateException())).isBetween(500L, 1500L);
  }

  /**
   * Observabilidade do breaker (fatia 9): cada tentativa que falha e será repetida e cada mudança
   * de estado do breaker vão para o log em WARN, só com o canal, o número da tentativa e o NOME DA
   * CLASSE da causa. A isca com telefone e e-mail na exceção não pode aparecer.
   */
  @Test
  void deveRegistrarATentativaRepetidaEAAberturaDoBreakerSoComCanalTentativaEClasseDaCausa() {
    String isca = "destino +5521999990000 isca.paciente@exemplo.invalid";
    RestClient semConexao =
        RestClient.builder()
            .baseUrl("http://localhost:1")
            .requestFactory(
                (uri, metodo) -> {
                  throw new java.net.ConnectException(isca);
                })
            .build();
    CanaisProperties.Canal canal =
        CanalDeTeste.para("http://localhost:1").tentativas(2).breaker(2).build();
    ClienteDeCanal cliente = new ClienteDeCanal("sms", canal, "/v1/x", "OK", semConexao);
    br.gov.saude.upa.DetectorDeDadoSensivel detector =
        new br.gov.saude.upa.DetectorDeDadoSensivel()
            .paciente("Fulana Isca", "+5521999990000", "isca.paciente@exemplo.invalid", null);
    br.gov.saude.upa.DetectorDeDadoSensivel.Captura captura =
        new br.gov.saude.upa.DetectorDeDadoSensivel.Captura();
    captura.ligarNaRaiz();

    ResultadoDoEnvio resultado;
    try {
      resultado = cliente.enviar(Map.of("destinatario", isca), NotificadorHttpContratoTest.CHAVE);
    } finally {
      captura.desligar();
    }

    assertThat(resultado).isEqualTo(new ResultadoDoEnvio.Esgotado());
    java.util.List<ILoggingEvent> doCanal =
        captura.eventos().stream()
            .filter(e -> e.getLoggerName().equals(ClienteDeCanal.class.getName()))
            .toList();
    assertThat(doCanal)
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
              assertThat(e.getFormattedMessage())
                  .isEqualTo(
                      "Canal sms tentativa 1 falhou e sera repetida: causa=java.net.ConnectException");
            });
    assertThat(doCanal)
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
              assertThat(e.getFormattedMessage())
                  .isEqualTo("Canal sms circuit breaker mudou de estado: CLOSED -> OPEN");
            });
    assertThat(doCanal).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    detector.exigirNenhumVazamento(captura.eventos());
  }

  @Test
  void deveDevolverEsgotadoRegistrarSoOTipoEContarNoBreakerQuandoOcorrerErroInesperado() {
    String segredo = "+5521999990000 texto-do-paciente";
    AtomicInteger criacoes = new AtomicInteger();
    RestClient quebrado =
        RestClient.builder()
            .requestFactory(
                (uri, metodo) -> {
                  criacoes.incrementAndGet();
                  throw new IllegalStateException(segredo);
                })
            .build();
    CanaisProperties.Canal canal =
        CanalDeTeste.para("http://localhost:1").tentativas(1).breaker(4).build();
    ClienteDeCanal cliente = new ClienteDeCanal("teste", canal, "/v1/x", "OK", quebrado);
    Logger logger = (Logger) LoggerFactory.getLogger("br.gov.saude.upa.infrastructure.canais");
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);

    try {
      for (int i = 0; i < 6; i++) {
        assertThat(
                cliente.enviar(Map.of("destinatario", segredo), NotificadorHttpContratoTest.CHAVE))
            .isEqualTo(new ResultadoDoEnvio.Esgotado());
      }
    } finally {
      logger.detachAppender(logs);
    }

    // Contou como falha: depois de 4 chamadas o breaker abriu e a 5ª e a 6ª nem tentam.
    assertThat(criacoes.get()).isEqualTo(4);
    assertThat(logs.list).isNotEmpty();
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(m -> m.contains("IllegalStateException"));
    for (ILoggingEvent evento : logs.list) {
      assertThat(evento.getFormattedMessage() + " " + evento.getThrowableProxy())
          .doesNotContain(segredo)
          .doesNotContain("5521999990000")
          .doesNotContain("texto-do-paciente");
    }
  }
}
