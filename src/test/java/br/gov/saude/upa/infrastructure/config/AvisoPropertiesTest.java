package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Padrão de produção do lote, lido do {@code application.properties} real. */
class AvisoPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AvisoProperties.class)
  static class SoAsPropriedades {}

  @Test
  void deveCarregarOPadraoDeProducaoDoLote() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(SoAsPropriedades.class)
        .run(
            contexto -> {
              assertThat(contexto).hasNotFailed();
              AvisoProperties aviso = contexto.getBean(AvisoProperties.class);
              // Calibrado pela carga (fatia 9): com 50/s no limitador, 15 envios simultâneos
              // mantêm a vazão sem enfileirar as chamadas dentro do adapter.
              assertThat(aviso.concorrenciaMaxima()).isEqualTo(15);
              assertThat(aviso.tempoLimiteReserva()).isEqualTo(Duration.ofSeconds(120));
            });
  }
}
