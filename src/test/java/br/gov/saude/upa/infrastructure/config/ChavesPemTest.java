package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.ChavesDeTeste;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ChavesPemTest {

  private static final DefaultResourceLoader RECURSOS = new DefaultResourceLoader();

  private static String pem(byte[] der) {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END PUBLIC KEY-----\n";
  }

  @Test
  void deveLerAChavePublicaComQuebrasDeLinhaReaisOuLiterais() {
    String pem = ChavesDeTeste.pemPublica();

    assertThat(ChavesPem.chavePublica(pem, RECURSOS)).isEqualTo(ChavesDeTeste.PAR.getPublic());
    assertThat(ChavesPem.chavePublica(pem.replace("\n", "\\n"), RECURSOS))
        .isEqualTo(ChavesDeTeste.PAR.getPublic());
  }

  @Test
  void deveRecusarChavePublicaComMenosDe2048Bits() throws Exception {
    KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
    gerador.initialize(1024);
    String fraca = pem(gerador.generateKeyPair().getPublic().getEncoded());

    assertThatThrownBy(() -> ChavesPem.chavePublica(fraca, RECURSOS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2048");
  }

  @Test
  void deveRecusarPemInvalidoSemCitarOConteudo() {
    String lixo = "-----BEGIN PUBLIC KEY-----\nconteudo-secreto-qualquer\n-----END PUBLIC KEY-----";

    assertThatThrownBy(() -> ChavesPem.chavePublica(lixo, RECURSOS))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("conteudo-secreto"));
    assertThatThrownBy(() -> ChavesPem.chavePublica("  ", RECURSOS))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ChavesPem.chavePublica("file:nao/existe.pem", RECURSOS))
        .isInstanceOf(IllegalStateException.class);
  }
}
