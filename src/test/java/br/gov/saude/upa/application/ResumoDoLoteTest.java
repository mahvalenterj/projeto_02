package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ResumoDoLoteTest {

  @Test
  void deveGuardarAsContagens() {
    ResumoDoLote resumo = new ResumoDoLote(7, 1, 2, 3, 4, 5, 6);

    assertThat(resumo.selecionados()).isEqualTo(7);
    assertThat(resumo.confirmados()).isEqualTo(1);
    assertThat(resumo.cancelados()).isEqualTo(2);
    assertThat(resumo.emRevisaoManual()).isEqualTo(3);
    assertThat(resumo.indeterminados()).isEqualTo(4);
    assertThat(resumo.pulados()).isEqualTo(5);
    assertThat(resumo.falhasInesperadas()).isEqualTo(6);
  }

  @Test
  void deveAceitarResumoZerado() {
    ResumoDoLote resumo = new ResumoDoLote(0, 0, 0, 0, 0, 0, 0);

    assertThat(resumo.selecionados()).isZero();
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarContagemNegativaEmQualquerCampo() {
    String[] campos = {
      "selecionados",
      "confirmados",
      "cancelados",
      "emRevisaoManual",
      "indeterminados",
      "pulados",
      "falhasInesperadas"
    };
    return IntStream.range(0, campos.length)
        .mapToObj(
            posicao ->
                DynamicTest.dynamicTest(
                    campos[posicao],
                    () -> {
                      int[] v = new int[campos.length];
                      v[posicao] = -1;
                      assertThatThrownBy(
                              () -> new ResumoDoLote(v[0], v[1], v[2], v[3], v[4], v[5], v[6]))
                          .isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining(campos[posicao]);
                    }));
  }
}
