package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Reconstituição do aviso a partir do que está gravado (usada pelo adapter de persistência). */
class AvisoPacienteReconstituicaoTest {

  private static final UUID ID_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ID_AGENDAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final Instant RESERVADO_EM = Instant.parse("2026-10-11T15:00:00Z");
  private static final Instant ATUALIZADO_EM = RESERVADO_EM.plusSeconds(30);
  private static final MotivoCancelamento MOTIVO = MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO;

  private record Valido(StatusAviso status, MotivoCancelamento motivo, Instant atualizadoEm) {}

  @TestFactory
  Stream<DynamicTest> deveReconstituirCadaStatusValido() {
    return Stream.of(
            new Valido(StatusAviso.PENDENTE, null, RESERVADO_EM),
            new Valido(StatusAviso.CONFIRMADO, null, ATUALIZADO_EM),
            new Valido(StatusAviso.CANCELADO, MOTIVO, ATUALIZADO_EM))
        .map(
            valido ->
                DynamicTest.dynamicTest(
                    valido.status().name(),
                    () -> {
                      AvisoPaciente aviso =
                          AvisoPaciente.reconstituir(
                              ID_EVENTO,
                              ID_AGENDAMENTO,
                              valido.status(),
                              RESERVADO_EM,
                              valido.atualizadoEm(),
                              valido.motivo());

                      assertThat(aviso.eventoId()).isEqualTo(ID_EVENTO);
                      assertThat(aviso.agendamentoId()).isEqualTo(ID_AGENDAMENTO);
                      assertThat(aviso.status()).isEqualTo(valido.status());
                      assertThat(aviso.reservadoEm()).isEqualTo(RESERVADO_EM);
                      assertThat(aviso.atualizadoEm()).isEqualTo(valido.atualizadoEm());
                      assertThat(aviso.motivoCancelamento())
                          .isEqualTo(java.util.Optional.ofNullable(valido.motivo()));
                    }));
  }

  @Test
  void aReservaPendenteReconstituidaDeveSeComportarComoReservada() {
    AvisoPaciente aviso =
        AvisoPaciente.reconstituir(
            ID_EVENTO, ID_AGENDAMENTO, StatusAviso.PENDENTE, RESERVADO_EM, RESERVADO_EM, null);
    Duration limite = Duration.ofMinutes(5);

    assertThat(aviso.estaAbandonada(RESERVADO_EM.plus(limite), limite)).isFalse();
    assertThat(aviso.estaAbandonada(RESERVADO_EM.plus(limite).plusNanos(1), limite)).isTrue();
  }

  @Test
  void deveRejeitarAtualizadoEmAnteriorAoReservadoEm() {
    assertThatThrownBy(
            () ->
                AvisoPaciente.reconstituir(
                    ID_EVENTO,
                    ID_AGENDAMENTO,
                    StatusAviso.PENDENTE,
                    RESERVADO_EM,
                    RESERVADO_EM.minusNanos(1),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ID_EVENTO.toString())
        .hasMessageContaining(ID_AGENDAMENTO.toString());
  }

  @Test
  void deveRejeitarCanceladoSemMotivo() {
    assertThatThrownBy(
            () ->
                AvisoPaciente.reconstituir(
                    ID_EVENTO,
                    ID_AGENDAMENTO,
                    StatusAviso.CANCELADO,
                    RESERVADO_EM,
                    ATUALIZADO_EM,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ID_EVENTO.toString());
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarMotivoQuandoOStatusNaoForCancelado() {
    return Stream.of(StatusAviso.PENDENTE, StatusAviso.CONFIRMADO)
        .map(
            status ->
                DynamicTest.dynamicTest(
                    status.name(),
                    () ->
                        assertThatThrownBy(
                                () ->
                                    AvisoPaciente.reconstituir(
                                        ID_EVENTO,
                                        ID_AGENDAMENTO,
                                        status,
                                        RESERVADO_EM,
                                        ATUALIZADO_EM,
                                        MOTIVO))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(ID_EVENTO.toString())));
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarCamposObrigatoriosNulos() {
    return IntStream.range(0, 5)
        .mapToObj(
            posicao ->
                DynamicTest.dynamicTest(
                    "campo na posicao " + posicao,
                    () -> {
                      Object[] campos = {
                        ID_EVENTO, ID_AGENDAMENTO, StatusAviso.PENDENTE, RESERVADO_EM, ATUALIZADO_EM
                      };
                      campos[posicao] = null;

                      assertThatThrownBy(
                              () ->
                                  AvisoPaciente.reconstituir(
                                      (UUID) campos[0],
                                      (UUID) campos[1],
                                      (StatusAviso) campos[2],
                                      (Instant) campos[3],
                                      (Instant) campos[4],
                                      null))
                          .isInstanceOf(NullPointerException.class);
                    }));
  }
}
