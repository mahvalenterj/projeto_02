package br.gov.saude.upa.infrastructure.canais;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração dos canais de aviso (spec 001 §10). {@code base-url} e {@code token} não têm valor
 * padrão e o token só entra por variável de ambiente; o contexto não sobe sem eles.
 */
@Validated
@ConfigurationProperties("upa.canais")
public record CanaisProperties(@Valid @NotNull Canal sms, @Valid @NotNull Canal email) {

  /** Um canal: endereço, credencial, timeouts do cliente HTTP e a política de resiliência. */
  public record Canal(
      @NotBlank String baseUrl,
      @NotBlank String token,
      @NotNull Duration timeoutConexao,
      @NotNull Duration timeoutLeitura,
      @Valid @NotNull PoliticaDeRetry retry,
      @Valid @NotNull PoliticaDeBreaker breaker,
      @Valid @NotNull PoliticaDeLimitador limitador,
      @Valid @NotNull PoliticaDeBulkhead bulkhead) {

    public Canal {
      positiva("timeoutConexao", timeoutConexao);
      positiva("timeoutLeitura", timeoutLeitura);
    }

    /** Sem token nem URL: a credencial não pode vazar por {@code toString}. */
    @Override
    public String toString() {
      return "Canal[timeoutConexao="
          + timeoutConexao
          + ", timeoutLeitura="
          + timeoutLeitura
          + ", retry="
          + retry
          + "]";
    }
  }

  /** Tentativas incluem a primeira; a espera dobra (multiplicador) com jitter proporcional. */
  public record PoliticaDeRetry(
      @Min(1) int tentativas,
      @NotNull Duration esperaInicial,
      @DecimalMin("1.0") double multiplicador,
      @DecimalMin("0.0") @DecimalMax(value = "1.0", inclusive = false) double jitter,
      @NotNull Duration retryAfterMaximo) {

    public PoliticaDeRetry {
      positiva("esperaInicial", esperaInicial);
      naoNegativa("retryAfterMaximo", retryAfterMaximo);
    }
  }

  /** {@code taxaDeFalha} em porcentagem (1 a 100). */
  public record PoliticaDeBreaker(
      @Min(1) int janela,
      @Min(1) int minimoDeChamadas,
      @Min(1) @Max(100) int taxaDeFalha,
      @NotNull Duration tempoAberto) {

    public PoliticaDeBreaker {
      positiva("tempoAberto", tempoAberto);
      if (minimoDeChamadas > janela) {
        throw new IllegalArgumentException(
            "upa.canais: minimoDeChamadas ("
                + minimoDeChamadas
                + ") não pode ser maior que janela ("
                + janela
                + ")");
      }
    }
  }

  /** {@code espera} zero é válido: o envio é negado na hora quando não há vaga. */
  public record PoliticaDeLimitador(
      @Min(1) int limite, @NotNull Duration periodo, @NotNull Duration espera) {

    public PoliticaDeLimitador {
      positiva("periodo", periodo);
      naoNegativa("espera", espera);
    }
  }

  /** {@code espera} zero é válido: o envio é negado na hora quando o bulkhead está cheio. */
  public record PoliticaDeBulkhead(@Min(1) int maximo, @NotNull Duration espera) {

    public PoliticaDeBulkhead {
      naoNegativa("espera", espera);
    }
  }

  /** Nulo passa: quem reporta é o {@code @NotNull}. */
  private static void positiva(String campo, Duration valor) {
    if (valor != null && (valor.isZero() || valor.isNegative())) {
      throw new IllegalArgumentException(
          "upa.canais: " + campo + " deve ser maior que zero (veio " + valor + ")");
    }
  }

  private static void naoNegativa(String campo, Duration valor) {
    if (valor != null && valor.isNegative()) {
      throw new IllegalArgumentException(
          "upa.canais: " + campo + " não pode ser negativo (veio " + valor + ")");
    }
  }
}
