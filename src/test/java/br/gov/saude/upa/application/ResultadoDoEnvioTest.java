package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResultadoDoEnvioTest {

  @Test
  void deveTerExatamenteTresDesfechosDistintos() {
    assertThat(ResultadoDoEnvio.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            ResultadoDoEnvio.Confirmado.class,
            ResultadoDoEnvio.Esgotado.class,
            ResultadoDoEnvio.Indeterminado.class);
  }

  @Test
  void deveConsiderarIguaisDesfechosDoMesmoTipo() {
    assertThat(new ResultadoDoEnvio.Confirmado()).isEqualTo(new ResultadoDoEnvio.Confirmado());
    assertThat(new ResultadoDoEnvio.Esgotado()).isNotEqualTo(new ResultadoDoEnvio.Indeterminado());
  }
}
