package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class AvisoPacienteTest {

  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ID_AGENDAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);
  private static final Duration LIMITE = Duration.ofMinutes(5);

  private static final List<ResultadoNotificacao> TODOS_OS_RESULTADOS =
      List.of(
          new ResultadoNotificacao.Enviada(),
          new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO),
          new ResultadoNotificacao.Indeterminada(),
          new ResultadoNotificacao.RevisaoManual(MotivoRevisaoManual.CANAIS_ESGOTADOS));

  private static AvisoPaciente reservado() {
    return AvisoPaciente.reservar(ID_EVENTO, ID_AGENDAMENTO, T0);
  }

  private static AvisoPaciente confirmado() {
    AvisoPaciente aviso = reservado();
    aviso.aplicar(new ResultadoNotificacao.Enviada(), T1);
    return aviso;
  }

  private static AvisoPaciente cancelado() {
    AvisoPaciente aviso = reservado();
    aviso.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO), T1);
    return aviso;
  }

  @Test
  void deveReservarComoPendente() {
    AvisoPaciente aviso = reservado();

    assertThat(aviso.eventoId()).isEqualTo(ID_EVENTO);
    assertThat(aviso.agendamentoId()).isEqualTo(ID_AGENDAMENTO);
    assertThat(aviso.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(aviso.reservadoEm()).isEqualTo(T0);
    assertThat(aviso.atualizadoEm()).isEqualTo(T0);
    assertThat(aviso.motivoCancelamento()).isEmpty();
  }

  @Test
  void deveConfirmarQuandoOCanalConfirmarOEnvio() {
    AvisoPaciente aviso = reservado();

    aviso.aplicar(new ResultadoNotificacao.Enviada(), T1);

    assertThat(aviso.status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(aviso.atualizadoEm()).isEqualTo(T1);
    assertThat(aviso.reservadoEm()).isEqualTo(T0);
  }

  @Test
  void deveCancelarComMotivoQuandoEquipamentoForRestabelecido() {
    AvisoPaciente aviso = reservado();

    aviso.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO), T1);

    assertThat(aviso.status()).isEqualTo(StatusAviso.CANCELADO);
    assertThat(aviso.motivoCancelamento()).contains(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);
    assertThat(aviso.atualizadoEm()).isEqualTo(T1);
  }

  @Test
  void deveManterPendenteQuandoResultadoForIndeterminada() {
    AvisoPaciente aviso = reservado();

    aviso.aplicar(new ResultadoNotificacao.Indeterminada(), T1);

    assertThat(aviso.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(aviso.atualizadoEm()).isEqualTo(T0);
  }

  @Test
  void deveManterPendenteQuandoResultadoForRevisaoManual() {
    AvisoPaciente aviso = reservado();

    aviso.aplicar(new ResultadoNotificacao.RevisaoManual(MotivoRevisaoManual.SEM_CONTATO), T1);

    assertThat(aviso.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(aviso.atualizadoEm()).isEqualTo(T0);
  }

  @TestFactory
  Stream<DynamicTest> soEnviadaDeveConfirmarOAviso() {
    return TODOS_OS_RESULTADOS.stream()
        .map(
            resultado ->
                DynamicTest.dynamicTest(
                    resultado.getClass().getSimpleName(),
                    () -> {
                      AvisoPaciente aviso = reservado();

                      aviso.aplicar(resultado, T1);

                      assertThat(aviso.status() == StatusAviso.CONFIRMADO)
                          .isEqualTo(resultado instanceof ResultadoNotificacao.Enviada);
                    }));
  }

  @Test
  void naoDeveConfirmarAvisoCancelado() {
    AvisoPaciente aviso = cancelado();

    assertThatThrownBy(() -> aviso.aplicar(new ResultadoNotificacao.Enviada(), T1.plusSeconds(1)))
        .isInstanceOf(TransicaoDeAvisoInvalidaException.class);

    assertThat(aviso.status()).isEqualTo(StatusAviso.CANCELADO);
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarResultadoQuandoAvisoNaoEstiverPendente() {
    return Stream.of(confirmado(), cancelado())
        .flatMap(
            aviso ->
                TODOS_OS_RESULTADOS.stream()
                    .map(
                        resultado ->
                            DynamicTest.dynamicTest(
                                aviso.status() + " + " + resultado.getClass().getSimpleName(),
                                () -> {
                                  StatusAviso statusAntes = aviso.status();

                                  assertThatThrownBy(
                                          () -> aviso.aplicar(resultado, T1.plusSeconds(5)))
                                      .isInstanceOf(TransicaoDeAvisoInvalidaException.class)
                                      .hasMessageContaining(ID_EVENTO.toString())
                                      .hasMessageContaining(ID_AGENDAMENTO.toString())
                                      .hasMessageContaining(statusAntes.name());

                                  assertThat(aviso.status()).isEqualTo(statusAntes);
                                  assertThat(aviso.atualizadoEm()).isEqualTo(T1);
                                })));
  }

  @Test
  void naoDeveConsiderarAbandonadaUmaReservaNoLimiteExato() {
    assertThat(reservado().estaAbandonada(T0.plus(LIMITE), LIMITE)).isFalse();
  }

  @Test
  void deveConsiderarAbandonadaUmaReservaUmNanossegundoDepoisDoLimite() {
    assertThat(reservado().estaAbandonada(T0.plus(LIMITE).plusNanos(1), LIMITE)).isTrue();
  }

  @Test
  void naoDeveConsiderarAbandonadaUmaReservaRecente() {
    assertThat(reservado().estaAbandonada(T0, LIMITE)).isFalse();
  }

  @Test
  void naoDeveConsiderarAbandonadoUmAvisoQueJaTemDesfecho() {
    Instant muitoDepois = T0.plus(Duration.ofDays(1));

    assertThat(confirmado().estaAbandonada(muitoDepois, LIMITE)).isFalse();
    assertThat(cancelado().estaAbandonada(muitoDepois, LIMITE)).isFalse();
  }

  @Test
  void deveReassumirReservaAbandonadaRenovandoOsInstantes() {
    AvisoPaciente aviso = reservado();
    Instant depoisDoLimite = T0.plus(LIMITE).plusSeconds(1);

    aviso.reassumir(depoisDoLimite, LIMITE);

    assertThat(aviso.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(aviso.reservadoEm()).isEqualTo(depoisDoLimite);
    assertThat(aviso.atualizadoEm()).isEqualTo(depoisDoLimite);
  }

  @Test
  void naoDeveReassumirReservaQueNaoEstaAbandonada() {
    AvisoPaciente aviso = reservado();
    Instant dentroDoLimite = T0.plus(LIMITE);

    assertThatThrownBy(() -> aviso.reassumir(dentroDoLimite, LIMITE))
        .isInstanceOf(TransicaoDeAvisoInvalidaException.class)
        .hasMessageContaining(ID_EVENTO.toString());

    assertThat(aviso.reservadoEm()).isEqualTo(T0);
  }

  @Test
  void naoDeveReassumirAvisoJaConfirmado() {
    AvisoPaciente aviso = confirmado();

    assertThatThrownBy(() -> aviso.reassumir(T0.plus(Duration.ofDays(1)), LIMITE))
        .isInstanceOf(TransicaoDeAvisoInvalidaException.class);

    assertThat(aviso.status()).isEqualTo(StatusAviso.CONFIRMADO);
  }

  @Test
  void deveRejeitarTempoLimiteInvalido() {
    AvisoPaciente aviso = reservado();

    assertThatThrownBy(() -> aviso.estaAbandonada(T1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aviso.estaAbandonada(T1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> aviso.estaAbandonada(T1, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    AvisoPaciente aviso = reservado();

    assertThatThrownBy(() -> AvisoPaciente.reservar(null, ID_AGENDAMENTO, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AvisoPaciente.reservar(ID_EVENTO, null, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AvisoPaciente.reservar(ID_EVENTO, ID_AGENDAMENTO, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aviso.aplicar(null, T1)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aviso.aplicar(new ResultadoNotificacao.Enviada(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aviso.estaAbandonada(null, LIMITE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aviso.reassumir(null, LIMITE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void toStringDeveMostrarSoOsIds() {
    String texto = reservado().toString();

    assertThat(texto).contains(ID_EVENTO.toString()).contains(ID_AGENDAMENTO.toString());
  }
}
