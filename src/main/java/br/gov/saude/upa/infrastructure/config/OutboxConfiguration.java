package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.application.AlertasDoOutbox;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.FilaDoOutbox;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o despachante do outbox e, se {@code upa.outbox.habilitado} (padrão: ligado), o agendador
 * que o dispara. Desligado, o despachante continua disponível para chamada direta.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxProperties.class, AvisoProperties.class})
@EnableScheduling
class OutboxConfiguration {

  @Bean
  AlertasDoOutbox alertasDoOutbox() {
    return new AlertasDoOutboxSlf4j();
  }

  @Bean
  DespachanteDoOutbox despachanteDoOutbox(
      FilaDoOutbox fila,
      NotificarPacientesAfetados lote,
      AlertasDoOutbox alertas,
      Clock clock,
      OutboxProperties p,
      AvisoProperties aviso) {
    if (p.atrasoDeReentrega().compareTo(aviso.tempoLimiteReserva()) <= 0) {
      throw new IllegalStateException(
          "upa.outbox.atraso-de-reentrega ("
              + p.atrasoDeReentrega()
              + ") deve ser maior que upa.aviso.tempo-limite-reserva ("
              + aviso.tempoLimiteReserva()
              + "): a reserva abandonada precisa já ter vencido na reentrega");
    }
    return new DespachanteDoOutbox(
        fila,
        lote,
        alertas,
        clock,
        p.tamanhoDoLote(),
        p.lease(),
        p.atrasoDeReentrega(),
        p.maximoDeTentativas());
  }

  @Bean
  @ConditionalOnProperty(
      name = "upa.outbox.habilitado",
      havingValue = "true",
      matchIfMissing = true)
  AgendadorDoOutbox agendadorDoOutbox(DespachanteDoOutbox despachante) {
    return new AgendadorDoOutbox(despachante);
  }
}
