package br.gov.saude.upa.infrastructure.canais;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CanaisPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(CanaisConfiguration.class);

  private static final String[] OBRIGATORIAS = {
    "upa.canais.sms.base-url=http://sms.invalid",
    "upa.canais.sms.token=token-sms",
    "upa.canais.email.base-url=http://email.invalid",
    "upa.canais.email.token=token-email"
  };

  @Test
  void deveCarregarOsValoresIniciaisDaSpecQuandoBaseUrlETokenVieremDoAmbiente() {
    runner
        .withPropertyValues(OBRIGATORIAS)
        .run(
            contexto -> {
              assertThat(contexto).hasNotFailed();
              CanaisProperties canais = contexto.getBean(CanaisProperties.class);
              assertThat(canais.sms().timeoutConexao()).isEqualTo(Duration.ofSeconds(2));
              assertThat(canais.sms().timeoutLeitura()).isEqualTo(Duration.ofSeconds(5));
              assertThat(canais.sms().retry().tentativas()).isEqualTo(3);
              assertThat(canais.sms().retry().esperaInicial()).isEqualTo(Duration.ofMillis(500));
              assertThat(canais.sms().breaker().janela()).isEqualTo(50);
              assertThat(canais.sms().breaker().minimoDeChamadas()).isEqualTo(20);
              assertThat(canais.sms().limitador().limite()).isEqualTo(50);
              assertThat(canais.sms().bulkhead().maximo()).isEqualTo(50);
              assertThat(canais.email().timeoutConexao()).isEqualTo(Duration.ofSeconds(3));
              assertThat(canais.email().timeoutLeitura()).isEqualTo(Duration.ofSeconds(10));
              assertThat(canais.email().retry().tentativas()).isEqualTo(2);
              assertThat(canais.email().retry().esperaInicial()).isEqualTo(Duration.ofSeconds(1));
              assertThat(canais.email().breaker().janela()).isEqualTo(20);
              assertThat(canais.email().breaker().minimoDeChamadas()).isEqualTo(10);
              assertThat(canais.email().retry().retryAfterMaximo())
                  .isEqualTo(Duration.ofSeconds(5));
            });
  }

  @Test
  void naoDeveSubirSemTokenDoSms() {
    runner
        .withPropertyValues(
            "upa.canais.sms.base-url=http://sms.invalid",
            "upa.canais.email.base-url=http://email.invalid",
            "upa.canais.email.token=token-email")
        .run(contexto -> assertThat(contexto).hasFailed());
  }

  @Test
  void naoDeveSubirSemBaseUrlDoEmail() {
    runner
        .withPropertyValues(
            "upa.canais.sms.base-url=http://sms.invalid",
            "upa.canais.sms.token=token-sms",
            "upa.canais.email.token=token-email")
        .run(contexto -> assertThat(contexto).hasFailed());
  }

  @Test
  void naoDeveSubirComTokenEmBranco() {
    runner
        .withPropertyValues(OBRIGATORIAS)
        .withPropertyValues("upa.canais.sms.token=  ")
        .run(contexto -> assertThat(contexto).hasFailed());
  }

  @Test
  void naoDeveSubirComNumeroDeTentativasInvalido() {
    runner
        .withPropertyValues(OBRIGATORIAS)
        .withPropertyValues("upa.canais.sms.retry.tentativas=0")
        .run(contexto -> assertThat(contexto).hasFailed());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "upa.canais.sms.timeout-conexao=PT0S|timeoutConexao",
        "upa.canais.sms.timeout-leitura=-PT1S|timeoutLeitura",
        "upa.canais.email.retry.espera-inicial=PT0S|esperaInicial",
        "upa.canais.sms.breaker.tempo-aberto=PT0S|tempoAberto",
        "upa.canais.sms.limitador.periodo=PT0S|periodo",
        "upa.canais.email.limitador.espera=-PT1S|espera",
        "upa.canais.sms.bulkhead.espera=-PT1S|espera",
        "upa.canais.sms.retry.retry-after-maximo=-PT1S|retryAfterMaximo",
        "upa.canais.sms.breaker.minimo-de-chamadas=51|minimoDeChamadas"
      })
  void naoDeveSubirComValorInvalidoENomearOCampoNaMensagem(String propriedade, String campo) {
    runner
        .withPropertyValues(OBRIGATORIAS)
        .withPropertyValues(propriedade)
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(contexto).getFailure().hasStackTraceContaining(campo);
            });
  }

  @Test
  void devePermitirEsperaZeroNoLimitadorENoBulkheadERetryAfterMaximoZero() {
    runner
        .withPropertyValues(OBRIGATORIAS)
        .withPropertyValues(
            "upa.canais.sms.limitador.espera=PT0S",
            "upa.canais.sms.bulkhead.espera=PT0S",
            "upa.canais.sms.retry.retry-after-maximo=PT0S")
        .run(contexto -> assertThat(contexto).hasNotFailed());
  }

  @Test
  void naoDeveMostrarOTokenNemAUrlNoToString() {
    CanaisProperties.Canal canal = CanalDeTeste.para("http://provedor.invalid").build();

    assertThat(canal.toString())
        .doesNotContain(CanalDeTeste.TOKEN)
        .doesNotContain("provedor.invalid");
    assertThat(new CanaisProperties(canal, canal).toString()).doesNotContain(CanalDeTeste.TOKEN);
  }
}
