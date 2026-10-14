package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PoliticaDeReservaTest {

  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ID_AGENDAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final Duration LIMITE = Duration.ofMinutes(5);
  private static final Instant DENTRO_DO_LIMITE = T0.plusSeconds(60);

  private final PoliticaDeReserva politica = new PoliticaDeReserva();

  private static AvisoPaciente pendente() {
    return AvisoPaciente.reservar(ID_EVENTO, ID_AGENDAMENTO, T0);
  }

  private static AvisoPaciente confirmado() {
    AvisoPaciente aviso = pendente();
    aviso.aplicar(new ResultadoNotificacao.Enviada(), T0.plusSeconds(10));
    return aviso;
  }

  private static AvisoPaciente cancelado() {
    AvisoPaciente aviso = pendente();
    aviso.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO),
        T0.plusSeconds(10));
    return aviso;
  }

  @Test
  void deveReservarQuandoNaoHouverAviso() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.empty(), false, DENTRO_DO_LIMITE, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Reservar());
  }

  @Test
  void devePularQuandoAvisoJaForConfirmado() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(confirmado()), false, DENTRO_DO_LIMITE, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.JA_CONFIRMADO));
  }

  @Test
  void devePularQuandoAvisoJaForCancelado() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(cancelado()), false, DENTRO_DO_LIMITE, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.JA_CANCELADO));
  }

  @Test
  void devePularQuandoJaEstiverEmRevisaoManual() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.empty(), true, DENTRO_DO_LIMITE, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_REVISAO_MANUAL));
  }

  @Test
  void aRevisaoManualDeveVencerUmaReservaPendenteAbandonada() {
    Instant muitoDepois = T0.plus(LIMITE).plusSeconds(600);

    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(pendente()), true, muitoDepois, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_REVISAO_MANUAL));
  }

  @Test
  void devePularQuandoReservaPendenteEstiverDentroDoTempoLimite() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(pendente()), false, DENTRO_DO_LIMITE, LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_ANDAMENTO));
  }

  @Test
  void naoDeveReassumirNoLimiteExato() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(pendente()), false, T0.plus(LIMITE), LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Pular(MotivoDoPulo.EM_ANDAMENTO));
  }

  @Test
  void deveReassumirReservaPendenteAbandonada() {
    DecisaoParaAgendamento decisao =
        politica.decidir(Optional.of(pendente()), false, T0.plus(LIMITE).plusNanos(1), LIMITE);

    assertThat(decisao).isEqualTo(new DecisaoParaAgendamento.Reassumir());
  }

  @Test
  void deveRejeitarTempoLimiteInvalidoMesmoSemAvisoExistente() {
    assertThatThrownBy(() -> politica.decidir(Optional.empty(), false, T0, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> politica.decidir(Optional.empty(), false, T0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> politica.decidir(Optional.empty(), false, T0, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    assertThatThrownBy(() -> politica.decidir(null, false, T0, LIMITE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> politica.decidir(Optional.empty(), false, null, LIMITE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void pularDeveRejeitarMotivoNulo() {
    assertThatThrownBy(() -> new DecisaoParaAgendamento.Pular(null))
        .isInstanceOf(NullPointerException.class);
  }
}
