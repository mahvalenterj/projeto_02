package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class StatusAvisoTest {

  /** ADR 0001: só PENDENTE evolui; CONFIRMADO e CANCELADO são terminais. */
  private static final Map<StatusAviso, Set<StatusAviso>> VALIDAS =
      Map.of(
          StatusAviso.PENDENTE, EnumSet.of(StatusAviso.CONFIRMADO, StatusAviso.CANCELADO),
          StatusAviso.CONFIRMADO, EnumSet.noneOf(StatusAviso.class),
          StatusAviso.CANCELADO, EnumSet.noneOf(StatusAviso.class));

  @TestFactory
  Stream<DynamicTest> deveAceitarSomenteAsTransicoesDaTabela() {
    return Stream.of(StatusAviso.values())
        .flatMap(
            origem ->
                Stream.of(StatusAviso.values())
                    .map(
                        destino ->
                            DynamicTest.dynamicTest(
                                origem + " -> " + destino,
                                () ->
                                    assertThat(origem.podeTransicionarPara(destino))
                                        .isEqualTo(VALIDAS.get(origem).contains(destino)))));
  }
}
