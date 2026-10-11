package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EquipamentoIndisponivelEventTest {

  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ID_EQUIPAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final Instant MOMENTO_FALHA = Instant.parse("2026-10-11T12:00:00Z");

  @Test
  void deveGuardarTodosOsCampos() {
    var evento = new EquipamentoIndisponivelEvent(ID_EVENTO, ID_EQUIPAMENTO, ID_UPA, MOMENTO_FALHA);

    assertThat(evento.eventoId()).isEqualTo(ID_EVENTO);
    assertThat(evento.idEquipamento()).isEqualTo(ID_EQUIPAMENTO);
    assertThat(evento.idUpa()).isEqualTo(ID_UPA);
    assertThat(evento.momentoFalha()).isEqualTo(MOMENTO_FALHA);
  }

  @Test
  void deveRejeitarEventoIdNulo() {
    assertThatThrownBy(
            () -> new EquipamentoIndisponivelEvent(null, ID_EQUIPAMENTO, ID_UPA, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("eventoId");
  }

  @Test
  void deveRejeitarIdEquipamentoNulo() {
    assertThatThrownBy(
            () -> new EquipamentoIndisponivelEvent(ID_EVENTO, null, ID_UPA, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("idEquipamento");
  }

  @Test
  void deveRejeitarIdUpaNulo() {
    assertThatThrownBy(
            () -> new EquipamentoIndisponivelEvent(ID_EVENTO, ID_EQUIPAMENTO, null, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("idUpa");
  }

  @Test
  void deveRejeitarMomentoFalhaNulo() {
    assertThatThrownBy(
            () -> new EquipamentoIndisponivelEvent(ID_EVENTO, ID_EQUIPAMENTO, ID_UPA, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("momentoFalha");
  }
}
