package br.gov.saude.upa.infrastructure.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.ChavesDeTeste;
import br.gov.saude.upa.infrastructure.config.ChavesPem;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * O emissor de dev só sobe com o par de dev (impressão digital da pública) e com um par que fecha
 * (assina e verifica um token no construtor).
 */
class EmissorDeTokenDeDesenvolvimentoTest {

  private static final DefaultResourceLoader RECURSOS = new DefaultResourceLoader();
  private static final Clock RELOGIO =
      Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), ZoneOffset.UTC);

  private static RSAPublicKey publicaDeDev() {
    return ChavesPem.chavePublica("file:config/dev/jwt/publica.pem", RECURSOS);
  }

  private static RSAPrivateKey privadaDeDev() {
    return ChavesPem.chavePrivada("file:config/dev/jwt/privada.pem", RECURSOS);
  }

  @Test
  void deveEmitirTokenRs256ComOParDeDev() throws Exception {
    EmissorDeTokenDeDesenvolvimento emissor =
        new EmissorDeTokenDeDesenvolvimento(
            publicaDeDev(), privadaDeDev(), "http://emissor.dev", "upa-api", RELOGIO);
    UUID upa = UUID.randomUUID();

    SignedJWT jwt = SignedJWT.parse(emissor.emitir(List.of(upa)));

    assertThat(jwt.getJWTClaimsSet().getStringListClaim("upas")).containsExactly(upa.toString());
  }

  @Test
  void deveRecusarSubirQuandoAChavePublicaAtivaNaoEADeDev() {
    assertThatThrownBy(
            () ->
                new EmissorDeTokenDeDesenvolvimento(
                    (RSAPublicKey) ChavesDeTeste.PAR.getPublic(),
                    (RSAPrivateKey) ChavesDeTeste.PAR.getPrivate(),
                    "http://emissor.dev",
                    "upa-api",
                    RELOGIO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("não é a de desenvolvimento");
  }

  @Test
  void deveRecusarSubirQuandoAChavePrivadaNaoFechaComAPublica() {
    assertThatThrownBy(
            () ->
                new EmissorDeTokenDeDesenvolvimento(
                    publicaDeDev(),
                    (RSAPrivateKey) ChavesDeTeste.PAR.getPrivate(),
                    "http://emissor.dev",
                    "upa-api",
                    RELOGIO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("não forma par");
  }
}
