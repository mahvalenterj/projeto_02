package br.gov.saude.upa.infrastructure.dev;

import br.gov.saude.upa.infrastructure.config.TravaDaChaveDeDesenvolvimento;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * SOMENTE DESENVOLVIMENTO: emite JWT RS256 com o par de chaves de dev ({@code config/dev/jwt}), com
 * as mesmas claims que a aplicação exige ({@code iss}, {@code aud}, {@code sub}, {@code iat},
 * {@code exp} de 15 min e {@code upas}). Em produção a emissão fica fora do sistema (ADR 0004).
 */
class EmissorDeTokenDeDesenvolvimento {

  static final Duration VALIDADE = Duration.ofMinutes(15);

  private final JwtEncoder encoder;
  private final String emissor;
  private final String audiencia;
  private final Clock relogio;

  EmissorDeTokenDeDesenvolvimento(
      RSAPublicKey publica,
      RSAPrivateKey privada,
      String emissor,
      String audiencia,
      Clock relogio) {
    Objects.requireNonNull(publica, "publica");
    Objects.requireNonNull(privada, "privada");
    if (!TravaDaChaveDeDesenvolvimento.ehChaveDeDesenvolvimento(publica)) {
      // A pública ativa é de outro emissor: emitir com o par de dev daria token que não valida, ou
      // pior, o perfil dev estaria rodando com uma chave real.
      throw new IllegalStateException(
          "A chave pública JWT ativa não é a de desenvolvimento (config/dev/jwt/publica.pem):"
              + " o emissor de token de dev não sobe");
    }
    RSAKey chave = new RSAKey.Builder(publica).privateKey(privada).build();
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(chave)));
    this.emissor = Objects.requireNonNull(emissor, "emissor");
    this.audiencia = Objects.requireNonNull(audiencia, "audiencia");
    this.relogio = Objects.requireNonNull(relogio, "relogio");
    exigirQueOParFecha(publica);
  }

  /** Assina um token e o verifica com a pública: se a privada não for do par, não sobe. */
  private void exigirQueOParFecha(RSAPublicKey publica) {
    NimbusJwtDecoder verificador = NimbusJwtDecoder.withPublicKey(publica).build();
    verificador.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    try {
      verificador.decode(emitir(List.of(UUID.randomUUID())));
    } catch (JwtException e) {
      throw new IllegalStateException(
          "A chave privada de dev não forma par com a chave pública ativa: o emissor de token de"
              + " dev não sobe");
    }
  }

  String emitir(List<UUID> upas) {
    Instant agora = relogio.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(emissor)
            .audience(List.of(audiencia))
            .subject("operador-dev")
            .issuedAt(agora)
            .expiresAt(agora.plus(VALIDADE))
            .claim("upas", upas.stream().map(UUID::toString).toList())
            .build();
    JwsHeader cabecalho = JwsHeader.with(SignatureAlgorithm.RS256).build();
    return encoder.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
  }
}
