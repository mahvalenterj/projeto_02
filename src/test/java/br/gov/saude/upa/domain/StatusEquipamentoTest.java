package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class StatusEquipamentoTest {

  /** Tabela de transições da spec 001 (§4): só DISPONIVEL e INDISPONIVEL se alternam. */
  private static final Map<StatusEquipamento, Set<StatusEquipamento>> VALIDAS =
      Map.of(
          StatusEquipamento.DISPONIVEL, EnumSet.of(StatusEquipamento.INDISPONIVEL),
          StatusEquipamento.INDISPONIVEL, EnumSet.of(StatusEquipamento.DISPONIVEL),
          StatusEquipamento.EM_MANUTENCAO, EnumSet.noneOf(StatusEquipamento.class));

  @TestFactory
  Stream<DynamicTest> deveAceitarSomenteAsTransicoesDaTabela() {
    return Stream.of(StatusEquipamento.values())
        .flatMap(
            origem ->
                Stream.of(StatusEquipamento.values())
                    .map(
                        destino ->
                            DynamicTest.dynamicTest(
                                origem + " -> " + destino,
                                () ->
                                    assertThat(origem.podeTransicionarPara(destino))
                                        .isEqualTo(VALIDAS.get(origem).contains(destino)))));
  }
}
