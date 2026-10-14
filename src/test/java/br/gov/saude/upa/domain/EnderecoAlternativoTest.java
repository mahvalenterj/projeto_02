package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EnderecoAlternativoTest {

  @Test
  void deveGuardarOTexto() {
    var endereco = new EnderecoAlternativo("UPA Cascadura, Rua Carolina Machado, 100");

    assertThat(endereco.texto()).isEqualTo("UPA Cascadura, Rua Carolina Machado, 100");
  }

  @Test
  void deveRejeitarTextoNulo() {
    assertThatThrownBy(() -> new EnderecoAlternativo(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveRejeitarTextoEmBranco() {
    assertThatThrownBy(() -> new EnderecoAlternativo(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EnderecoAlternativo("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
