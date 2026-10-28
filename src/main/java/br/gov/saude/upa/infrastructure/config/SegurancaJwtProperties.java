package br.gov.saude.upa.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT RS256 (ADR 0004). {@code chave-publica} é o PEM da chave pública, vindo de {@code
 * UPA_SEGURANCA_JWT_CHAVE_PUBLICA} (no perfil dev, o endereço {@code
 * file:config/dev/jwt/publica.pem}); sem valor padrão. {@code emissor} e {@code audiencia} são os
 * valores exigidos em {@code iss} e {@code aud}.
 */
@Validated
@ConfigurationProperties("upa.seguranca.jwt")
public record SegurancaJwtProperties(
    @NotBlank String chavePublica, @NotBlank String emissor, @NotBlank String audiencia) {

  /** O PEM não vai para o log por {@code toString}. */
  @Override
  public String toString() {
    return "SegurancaJwtProperties[emissor=" + emissor + ", audiencia=" + audiencia + "]";
  }
}
