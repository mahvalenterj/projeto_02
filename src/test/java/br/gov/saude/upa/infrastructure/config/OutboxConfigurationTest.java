package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.FilaDoOutbox;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import br.gov.saude.upa.application.Reivindicacao;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OutboxConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  static class Dependencias {

    @Bean
    FilaDoOutbox fila() {
      FilaDoOutbox fila = mock(FilaDoOutbox.class);
      when(fila.reivindicar(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyInt()))
          .thenReturn(Reivindicacao.vazia());
      return fila;
    }

    @Bean
    NotificarPacientesAfetados lote() {
      return mock(NotificarPacientesAfetados.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  // Intervalo longo: o agendador, quando ligado, roda uma vez e não atrapalha o teste.
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Dependencias.class, OutboxConfiguration.class)
          .withPropertyValues("upa.outbox.intervalo=PT1H");

  @Test
  void deveCarregarOsValoresIniciaisDoPlano() {
    runner.run(
        contexto -> {
          assertThat(contexto).hasNotFailed();
          OutboxProperties p = contexto.getBean(OutboxProperties.class);
          assertThat(p.tamanhoDoLote()).isEqualTo(10);
          assertThat(p.lease()).isEqualTo(Duration.ofMinutes(5));
          assertThat(p.atrasoDeReentrega()).isEqualTo(Duration.ofSeconds(130));
          assertThat(p.maximoDeTentativas()).isEqualTo(5);
          assertThat(p.habilitado()).isTrue();
          assertThat(contexto).hasSingleBean(DespachanteDoOutbox.class);
        });
  }

  @Test
  void deveCriarOAgendadorQuandoHabilitado() {
    runner
        .withPropertyValues("upa.outbox.habilitado=true")
        .run(contexto -> assertThat(contexto).hasSingleBean(AgendadorDoOutbox.class));
  }

  @Test
  void deveCriarOAgendadorQuandoAPropriedadeNaoForInformada() {
    new ApplicationContextRunner()
        .withUserConfiguration(Dependencias.class, OutboxConfiguration.class)
        .withPropertyValues(
            "upa.outbox.intervalo=PT1H",
            "upa.outbox.tamanho-do-lote=10",
            "upa.outbox.lease=PT10M",
            "upa.outbox.atraso-de-reentrega=PT130S",
            "upa.outbox.maximo-de-tentativas=5",
            "upa.aviso.tempo-limite-reserva=PT120S",
            "upa.aviso.concorrencia-maxima=50")
        .run(contexto -> assertThat(contexto).hasSingleBean(AgendadorDoOutbox.class));
  }

  @Test
  void naoDeveCriarOAgendadorQuandoDesabilitadoMasMantemODespachante() {
    runner
        .withPropertyValues("upa.outbox.habilitado=false")
        .run(
            contexto -> {
              assertThat(contexto).hasNotFailed();
              assertThat(contexto).doesNotHaveBean(AgendadorDoOutbox.class);
              assertThat(contexto).hasSingleBean(DespachanteDoOutbox.class);
            });
  }

  @Test
  void naoDeveSubirQuandoOLeaseForZeroECitarOCampo() {
    runner
        .withPropertyValues("upa.outbox.lease=PT0S")
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(mensagensDaCausa(contexto.getStartupFailure())).contains("lease");
            });
  }

  @Test
  void naoDeveSubirQuandoOLeaseForNegativoECitarOCampo() {
    runner
        .withPropertyValues("upa.outbox.lease=-PT1M")
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(mensagensDaCausa(contexto.getStartupFailure())).contains("lease");
            });
  }

  @Test
  void naoDeveSubirQuandoOTamanhoDoLoteForZeroECitarOCampo() {
    runner
        .withPropertyValues("upa.outbox.tamanho-do-lote=0")
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(mensagensDaCausa(contexto.getStartupFailure()))
                  .containsIgnoringCase("tamanhoDoLote");
            });
  }

  @Test
  void naoDeveSubirQuandoOAtrasoDeReentregaNaoSuperaOTempoLimiteDaReserva() {
    runner
        .withPropertyValues(
            "upa.outbox.atraso-de-reentrega=PT30S", "upa.aviso.tempo-limite-reserva=PT120S")
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(mensagensDaCausa(contexto.getStartupFailure()))
                  .contains("atraso-de-reentrega")
                  .contains("tempo-limite-reserva");
            });
  }

  @Test
  void naoDeveSubirQuandoOAtrasoDeReentregaForIgualAoTempoLimiteDaReserva() {
    runner
        .withPropertyValues(
            "upa.outbox.atraso-de-reentrega=PT120S", "upa.aviso.tempo-limite-reserva=PT120S")
        .run(
            contexto -> {
              assertThat(contexto).hasFailed();
              assertThat(mensagensDaCausa(contexto.getStartupFailure()))
                  .contains("atraso-de-reentrega")
                  .contains("tempo-limite-reserva");
            });
  }

  @Test
  void deveSubirQuandoOAtrasoDeReentregaSuperaOTempoLimiteDaReserva() {
    runner
        .withPropertyValues(
            "upa.outbox.atraso-de-reentrega=PT130S", "upa.aviso.tempo-limite-reserva=PT120S")
        .run(contexto -> assertThat(contexto).hasNotFailed());
  }

  private static String mensagensDaCausa(Throwable falha) {
    StringBuilder todas = new StringBuilder();
    for (Throwable t = falha; t != null; t = t.getCause()) {
      todas.append(t.getMessage()).append('\n');
    }
    return todas.toString();
  }
}
