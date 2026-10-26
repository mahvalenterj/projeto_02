package br.gov.saude.upa.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parâmetros do despachante do outbox. O lease precisa cobrir o lote de UM evento; o atraso de
 * reentrega deve superar o tempo-limite da reserva ({@code upa.aviso}), para que a reserva
 * abandonada já valha como expirada na reentrega.
 */
@Validated
@ConfigurationProperties("upa.outbox")
public record OutboxProperties(
    @Min(1) int tamanhoDoLote,
    @NotNull Duration lease,
    @NotNull Duration atrasoDeReentrega,
    @Min(1) int maximoDeTentativas,
    @NotNull Duration intervalo,
    boolean habilitado) {

  public OutboxProperties {
    positiva("lease", lease);
    positiva("atrasoDeReentrega", atrasoDeReentrega);
    positiva("intervalo", intervalo);
  }

  /** Nulo passa: quem reporta é o {@code @NotNull}. */
  private static void positiva(String campo, Duration valor) {
    if (valor != null && (valor.isZero() || valor.isNegative())) {
      throw new IllegalArgumentException(
          "upa.outbox: " + campo + " deve ser maior que zero (veio " + valor + ")");
    }
  }
}
