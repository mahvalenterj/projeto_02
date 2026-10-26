package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChaveDeIdempotenciaTest {

  private static final UUID EVENTO = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID AGENDAMENTO = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void deveRejeitarEventoNulo() {
    assertThatThrownBy(() -> new ChaveDeIdempotencia(null, AGENDAMENTO))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveRejeitarAgendamentoNulo() {
    assertThatThrownBy(() -> new ChaveDeIdempotencia(EVENTO, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveSerIgualPorValor() {
    var a = new ChaveDeIdempotencia(EVENTO, AGENDAMENTO);
    var b = new ChaveDeIdempotencia(EVENTO, AGENDAMENTO);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(new ChaveDeIdempotencia(AGENDAMENTO, EVENTO));
  }

  @Test
  void deveMostrarNoToStringSoOsIds() {
    assertThat(new ChaveDeIdempotencia(EVENTO, AGENDAMENTO).toString())
        .isEqualTo(
            "ChaveDeIdempotencia[eventoId=" + EVENTO + ", agendamentoId=" + AGENDAMENTO + "]");
  }
}
