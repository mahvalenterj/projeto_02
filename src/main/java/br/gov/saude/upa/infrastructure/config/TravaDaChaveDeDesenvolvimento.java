package br.gov.saude.upa.infrastructure.config;

import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

/**
 * Trava na subida (ADR 0004): a chave pública de DESENVOLVIMENTO ({@code
 * config/dev/jwt/publica.pem}) só vale com o perfil dev ativo. A privada correspondente está no
 * repositório: em qualquer outro perfil, aceitar essa chave permitiria a qualquer um forjar token
 * de qualquer UPA. A chave é reconhecida pela impressão digital (SHA-256 do DER), não pelo caminho
 * do arquivo, então copiar o PEM para a variável de ambiente também é barrado.
 */
public final class TravaDaChaveDeDesenvolvimento {

  /** SHA-256 do DER de {@code config/dev/jwt/publica.pem}; trocar o par exige atualizar aqui. */
  public static final String IMPRESSAO_DIGITAL_DA_CHAVE_DE_DEV =
      "f72419ec89a3d97072456efdbc1c327dd92f61c29a76806d1dac25a28898376f";

  private TravaDaChaveDeDesenvolvimento() {}

  public static boolean ehChaveDeDesenvolvimento(RSAPublicKey chave) {
    Objects.requireNonNull(chave, "chave");
    return IMPRESSAO_DIGITAL_DA_CHAVE_DE_DEV.equals(ChavesPem.impressaoDigital(chave));
  }

  /**
   * @throws IllegalStateException se a chave é a de dev e o perfil dev não está ativo
   */
  public static void verificar(RSAPublicKey chave, boolean perfilDevAtivo) {
    if (!perfilDevAtivo && ehChaveDeDesenvolvimento(chave)) {
      throw new IllegalStateException(
          "A chave pública JWT configurada é a chave pública de DESENVOLVIMENTO"
              + " (config/dev/jwt/publica.pem, cuja privada é pública) e o perfil dev não está"
              + " ativo: a aplicação não sobe. Configure UPA_SEGURANCA_JWT_CHAVE_PUBLICA com a"
              + " chave do emissor real.");
    }
  }
}
