package br.gov.saude.upa.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exige {@code iat} e {@code exp}, {@code iat} não no futuro (além da tolerância) e {@code exp -
 * iat} até o máximo (ADR 0004: sem revogação, o token roubado vale só até expirar). O {@code
 * JwtTimestampValidator} do Spring aceita token sem {@code exp}; este não.
 */
final class DuracaoMaximaDoToken implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2TokenValidatorResult RECUSADO =
      OAuth2TokenValidatorResult.failure(
          new OAuth2Error(
              OAuth2ErrorCodes.INVALID_TOKEN, "Validade do token fora da política", null));

  private final Duration maxima;
  private final Duration tolerancia;
  private final Clock relogio;

  DuracaoMaximaDoToken(Duration maxima, Duration tolerancia, Clock relogio) {
    this.maxima = Objects.requireNonNull(maxima, "maxima");
    this.tolerancia = Objects.requireNonNull(tolerancia, "tolerancia");
    this.relogio = Objects.requireNonNull(relogio, "relogio");
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    Instant emitido = jwt.getIssuedAt();
    Instant expira = jwt.getExpiresAt();
    if (emitido == null || expira == null) {
      return RECUSADO;
    }
    if (emitido.isAfter(relogio.instant().plus(tolerancia))) {
      return RECUSADO;
    }
    if (!expira.isAfter(emitido) || Duration.between(emitido, expira).compareTo(maxima) > 0) {
      return RECUSADO;
    }
    return OAuth2TokenValidatorResult.success();
  }
}
