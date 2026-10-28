package br.gov.saude.upa.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Lê chaves RSA em PEM: a pública em X.509 ({@code BEGIN PUBLIC KEY}) e a privada em PKCS#8 ({@code
 * BEGIN PRIVATE KEY}). O valor é o próprio PEM (como vem da variável de ambiente, com quebras de
 * linha reais ou {@code \n} literais) ou o endereço de um recurso ({@code file:...}). Nenhuma
 * mensagem de erro cita o conteúdo da chave.
 */
public final class ChavesPem {

  private static final String PUBLICA = "PUBLIC KEY";
  private static final String PRIVADA = "PRIVATE KEY";
  static final int TAMANHO_MINIMO_EM_BITS = 2048;

  private ChavesPem() {}

  public static RSAPublicKey chavePublica(String valor, ResourceLoader recursos) {
    byte[] der = der(conteudo(valor, recursos), PUBLICA);
    RSAPublicKey chave;
    try {
      chave =
          (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException("Chave pública RSA inválida (esperado PEM X.509)");
    }
    if (chave.getModulus().bitLength() < TAMANHO_MINIMO_EM_BITS) {
      throw new IllegalStateException(
          "Chave pública RSA fraca: o mínimo é " + TAMANHO_MINIMO_EM_BITS + " bits");
    }
    return chave;
  }

  /** SHA-256 em hexadecimal da chave pública em DER (X.509 SubjectPublicKeyInfo). */
  public static String impressaoDigital(RSAPublicKey chave) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(chave.getEncoded()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível na JVM", e);
    }
  }

  public static RSAPrivateKey chavePrivada(String valor, ResourceLoader recursos) {
    byte[] der = der(conteudo(valor, recursos), PRIVADA);
    try {
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException("Chave privada RSA inválida (esperado PEM PKCS#8)");
    }
  }

  private static String conteudo(String valor, ResourceLoader recursos) {
    if (valor == null || valor.isBlank()) {
      throw new IllegalStateException("Chave RSA ausente");
    }
    String limpo = valor.strip();
    if (limpo.startsWith("-----BEGIN")) {
      return limpo;
    }
    Resource recurso = recursos.getResource(limpo);
    try (InputStream entrada = recurso.getInputStream()) {
      return new String(entrada.readAllBytes(), StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new IllegalStateException("Não foi possível ler a chave RSA no endereço configurado");
    }
  }

  private static byte[] der(String pem, String tipo) {
    String inicio = "-----BEGIN " + tipo + "-----";
    String fim = "-----END " + tipo + "-----";
    String texto = pem.replace("\\n", "\n").strip();
    if (!texto.startsWith(inicio) || !texto.endsWith(fim)) {
      throw new IllegalStateException("Chave RSA fora do formato PEM esperado (" + tipo + ")");
    }
    String base64 = texto.substring(inicio.length(), texto.length() - fim.length());
    try {
      return Base64.getMimeDecoder().decode(base64.replaceAll("\\s", ""));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Chave RSA com Base64 inválido (" + tipo + ")");
    }
  }
}
