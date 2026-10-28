package br.gov.saude.upa;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Par de chaves RSA gerado NA EXECUÇÃO dos testes (nunca a chave de dev de {@code config/dev}) e
 * fábrica de tokens RS256. {@link #OUTRO} é um segundo par, para provar a recusa de assinatura que
 * não é da chave configurada.
 */
public final class ChavesDeTeste {

  public static final String EMISSOR = "https://emissor.teste.invalid";
  public static final String AUDIENCIA = "upa-api-teste";

  public static final KeyPair PAR = gerar();
  public static final KeyPair OUTRO = gerar();

  private ChavesDeTeste() {}

  private static KeyPair gerar() {
    try {
      KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
      gerador.initialize(2048);
      return gerador.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Chave pública em PEM (X.509 SubjectPublicKeyInfo), como viria da variável de ambiente. */
  public static String pemPublica() {
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(PAR.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
  }

  /** Token válido para as UPAs pedidas, emitido em {@code agora} e válido por 15 min. */
  public static String token(Instant agora, UUID... upas) {
    return novo(agora).upas(upas).assinar();
  }

  public static Construtor novo(Instant agora) {
    return new Construtor(agora);
  }

  /** Monta um token claim a claim (para os casos de token inválido). */
  public static final class Construtor {

    private final JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
    private PrivateKey chave = PAR.getPrivate();
    private JWSAlgorithm algoritmo = JWSAlgorithm.RS256;

    private Construtor(Instant agora) {
      claims
          .issuer(EMISSOR)
          .audience(AUDIENCIA)
          .subject("operador-teste")
          .issueTime(Date.from(agora))
          .expirationTime(Date.from(agora.plus(Duration.ofMinutes(15))))
          .claim("upas", List.of());
    }

    public Construtor upas(UUID... upas) {
      claims.claim("upas", java.util.Arrays.stream(upas).map(UUID::toString).toList());
      return this;
    }

    public Construtor emissor(String emissor) {
      claims.issuer(emissor);
      return this;
    }

    public Construtor audiencia(String audiencia) {
      claims.audience(audiencia);
      return this;
    }

    public Construtor sujeito(String sujeito) {
      claims.subject(sujeito);
      return this;
    }

    public Construtor emitidoEm(Instant iat) {
      claims.issueTime(iat == null ? null : Date.from(iat));
      return this;
    }

    public Construtor expiraEm(Instant exp) {
      claims.expirationTime(exp == null ? null : Date.from(exp));
      return this;
    }

    public Construtor claims(Map<String, Object> extras) {
      extras.forEach(claims::claim);
      return this;
    }

    public Construtor assinadoCom(PrivateKey outra) {
      this.chave = outra;
      return this;
    }

    public Construtor algoritmo(JWSAlgorithm outro) {
      this.algoritmo = outro;
      return this;
    }

    public String assinar() {
      try {
        SignedJWT jwt = new SignedJWT(new JWSHeader(algoritmo), claims.build());
        jwt.sign(new RSASSASigner(chave));
        return jwt.serialize();
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
