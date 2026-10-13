package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidatoAAvisoTest {

  private static final UUID ID_AGENDAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final LocalDate NASCIMENTO = LocalDate.of(1956, 3, 5);
  private static final AgendamentoExame AGENDAMENTO =
      new AgendamentoTomografia(
          ID_AGENDAMENTO,
          UUID.fromString("00000000-0000-0000-0000-0000000000d2"),
          UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
          UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
          Instant.parse("2026-10-01T09:00:00Z"),
          Instant.parse("2026-10-11T18:30:00Z"));

  @Test
  void deveGuardarAgendamentoEDataDeNascimento() {
    var candidato = new CandidatoAAviso(AGENDAMENTO, NASCIMENTO);

    assertThat(candidato.agendamento()).isSameAs(AGENDAMENTO);
    assertThat(candidato.dataNascimentoPaciente()).isEqualTo(NASCIMENTO);
  }

  @Test
  void deveRejeitarCamposNulos() {
    assertThatThrownBy(() -> new CandidatoAAviso(null, NASCIMENTO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CandidatoAAviso(AGENDAMENTO, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void toStringDeveMostrarSoOIdDoAgendamento() {
    String texto = new CandidatoAAviso(AGENDAMENTO, NASCIMENTO).toString();

    assertThat(texto).contains(ID_AGENDAMENTO.toString());
    assertThat(texto).doesNotContain(NASCIMENTO.toString()).doesNotContain("1956");
  }
}
