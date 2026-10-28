package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.ChavesDeTeste;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Trava na subida: a chave pública de DESENVOLVIMENTO ({@code config/dev/jwt/publica.pem}) só é
 * aceita com o perfil dev ativo. Qualquer um tem a privada correspondente (está no repositório): em
 * outro perfil ela permitiria forjar token de qualquer UPA.
 */
class TravaDaChaveDeDesenvolvimentoTest {

  private static RSAPublicKey chaveDeDev() {
    return ChavesPem.chavePublica("file:config/dev/jwt/publica.pem", new DefaultResourceLoader());
  }

  @Test
  void aImpressaoDigitalGuardadaDeveSerADaChavePublicaDeDevDoRepositorio() {
    assertThat(ChavesPem.impressaoDigital(chaveDeDev()))
        .isEqualTo(TravaDaChaveDeDesenvolvimento.IMPRESSAO_DIGITAL_DA_CHAVE_DE_DEV)
        .isEqualTo("f72419ec89a3d97072456efdbc1c327dd92f61c29a76806d1dac25a28898376f");
  }

  @Test
  void deveRecusarSubirComAChaveDeDevForaDoPerfilDev() {
    assertThatThrownBy(() -> TravaDaChaveDeDesenvolvimento.verificar(chaveDeDev(), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("chave pública de DESENVOLVIMENTO")
        .hasMessageContaining("perfil dev não está ativo");
  }

  @Test
  void deveAceitarAChaveDeDevNoPerfilDev() {
    assertThatCode(() -> TravaDaChaveDeDesenvolvimento.verificar(chaveDeDev(), true))
        .doesNotThrowAnyException();
  }

  @Test
  void deveAceitarOutraChaveForaDoPerfilDev() {
    RSAPublicKey outra = (RSAPublicKey) ChavesDeTeste.PAR.getPublic();

    assertThatCode(() -> TravaDaChaveDeDesenvolvimento.verificar(outra, false))
        .doesNotThrowAnyException();
    assertThat(TravaDaChaveDeDesenvolvimento.ehChaveDeDesenvolvimento(outra)).isFalse();
    assertThat(TravaDaChaveDeDesenvolvimento.ehChaveDeDesenvolvimento(chaveDeDev())).isTrue();
  }
}
