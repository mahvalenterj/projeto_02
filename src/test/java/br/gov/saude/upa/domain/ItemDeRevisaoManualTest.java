package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ItemDeRevisaoManualTest {

  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ID_AGENDAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Instant REGISTRADO_EM = Instant.parse("2026-10-11T15:03:00Z");

  @Test
  void deveGuardarTodosOsCampos() {
    var item =
        new ItemDeRevisaoManual(
            ID_EVENTO, ID_AGENDAMENTO, MotivoRevisaoManual.SEM_CONTATO, REGISTRADO_EM);

    assertThat(item.eventoId()).isEqualTo(ID_EVENTO);
    assertThat(item.agendamentoId()).isEqualTo(ID_AGENDAMENTO);
    assertThat(item.motivo()).isEqualTo(MotivoRevisaoManual.SEM_CONTATO);
    assertThat(item.registradoEm()).isEqualTo(REGISTRADO_EM);
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarCampoNulo() {
    return IntStream.range(0, 4)
        .mapToObj(
            posicao ->
                DynamicTest.dynamicTest(
                    "campo na posicao " + posicao,
                    () -> {
                      Object[] campos = {
                        ID_EVENTO, ID_AGENDAMENTO, MotivoRevisaoManual.SEM_CONTATO, REGISTRADO_EM
                      };
                      campos[posicao] = null;

                      assertThatThrownBy(
                              () ->
                                  new ItemDeRevisaoManual(
                                      (UUID) campos[0],
                                      (UUID) campos[1],
                                      (MotivoRevisaoManual) campos[2],
                                      (Instant) campos[3]))
                          .isInstanceOf(NullPointerException.class);
                    }));
  }

  @Test
  void toStringDeveMostrarSoOsIds() {
    String texto =
        new ItemDeRevisaoManual(
                ID_EVENTO, ID_AGENDAMENTO, MotivoRevisaoManual.CANAIS_ESGOTADOS, REGISTRADO_EM)
            .toString();

    assertThat(texto).contains(ID_EVENTO.toString()).contains(ID_AGENDAMENTO.toString());
    assertThat(texto).doesNotContain(REGISTRADO_EM.toString()).doesNotContain("CANAIS_ESGOTADOS");
  }
}
