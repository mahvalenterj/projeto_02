package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.PostgresIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de fluxo inteiro com os dois canais no WireMock (ponta a ponta e privacidade de
 * log): um único contexto Spring e um único container Postgres para as subclasses, que não declaram
 * configuração própria (a chave de contexto é a mesma). Relógio fixo, concorrência 1 e retries
 * rápidos. Os servidores sobem uma vez e só param com a JVM: um {@code @AfterAll} pararia o
 * WireMock que o contexto em cache ainda usa. Cada subclasse limpa o que criou, por ids próprios.
 */
@Import(CanaisSimuladosIntegracaoTest.RelogioFixo.class)
abstract class CanaisSimuladosIntegracaoTest extends PostgresIntegrationTest {

  static final Instant AGORA = Instant.parse("2026-10-11T15:00:00Z");

  static final WireMockServer SMS =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer EMAIL =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    SMS.start();
    EMAIL.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  SMS.stop();
                  EMAIL.stop();
                }));
  }

  @DynamicPropertySource
  static void canais(DynamicPropertyRegistry registro) {
    registro.add("upa.canais.sms.base-url", SMS::baseUrl);
    registro.add("upa.canais.email.base-url", EMAIL::baseUrl);
    registro.add("upa.canais.sms.retry.tentativas", () -> "2");
    registro.add("upa.canais.sms.retry.espera-inicial", () -> "PT0.01S");
    registro.add("upa.canais.email.retry.espera-inicial", () -> "PT0.01S");
    registro.add("upa.aviso.concorrencia-maxima", () -> "1");
    // O contexto (e o breaker de cada canal) é o mesmo para o ponta a ponta e a privacidade: as
    // falhas plantadas por um somam na janela do outro (SMS chegava a 19 de mínimo 20; e-mail a 8
    // de 10) e o breaker poderia abrir no meio de um fluxo que não fala dele. Aqui ele não abre;
    // a abertura continua provada nos testes de contrato dos canais (NotificadorHttpContratoTest).
    registro.add("upa.canais.sms.breaker.janela", () -> "1000");
    registro.add("upa.canais.sms.breaker.minimo-de-chamadas", () -> "1000");
    registro.add("upa.canais.email.breaker.janela", () -> "1000");
    registro.add("upa.canais.email.breaker.minimo-de-chamadas", () -> "1000");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RelogioFixo {

    @Bean
    @Primary
    Clock relogioDeTeste() {
      return Clock.fixed(AGORA, ZoneOffset.UTC);
    }
  }
}
