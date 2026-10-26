package br.gov.saude.upa.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parâmetros do lote de avisos (spec 001 §10). O tempo-limite da reserva decide quando uma reserva
 * abandonada pode ser reassumida; a concorrência limita os envios simultâneos de um lote.
 */
@Validated
@ConfigurationProperties("upa.aviso")
public record AvisoProperties(
    @NotNull Duration tempoLimiteReserva, @Min(1) int concorrenciaMaxima) {

  public AvisoProperties {
    if (tempoLimiteReserva != null
        && (tempoLimiteReserva.isZero() || tempoLimiteReserva.isNegative())) {
      throw new IllegalArgumentException(
          "upa.aviso: tempoLimiteReserva deve ser maior que zero (veio "
              + tempoLimiteReserva
              + ")");
    }
  }
}
