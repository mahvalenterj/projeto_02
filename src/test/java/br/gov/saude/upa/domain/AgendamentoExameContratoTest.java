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

/** Contrato comum de toda implementação de {@link AgendamentoExame} (princípio L do SOLID). */
abstract class AgendamentoExameContratoTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID ID_PACIENTE = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ID_EQUIPAMENTO =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant INICIO_EXAME = Instant.parse("2026-10-11T18:30:00Z");

  abstract AgendamentoExame criar(
      UUID id,
      UUID idPaciente,
      UUID idUpa,
      UUID idEquipamento,
      Instant criadoEm,
      Instant inicioExame);

  @Test
  void deveExporTodosOsCampos() {
    AgendamentoExame agendamento =
        criar(ID, ID_PACIENTE, ID_UPA, ID_EQUIPAMENTO, CRIADO_EM, INICIO_EXAME);

    assertThat(agendamento.id()).isEqualTo(ID);
    assertThat(agendamento.idPaciente()).isEqualTo(ID_PACIENTE);
    assertThat(agendamento.idUpa()).isEqualTo(ID_UPA);
    assertThat(agendamento.idEquipamento()).isEqualTo(ID_EQUIPAMENTO);
    assertThat(agendamento.criadoEm()).isEqualTo(CRIADO_EM);
    assertThat(agendamento.inicioExame()).isEqualTo(INICIO_EXAME);
  }

  @TestFactory
  Stream<DynamicTest> deveRejeitarCampoNulo() {
    return IntStream.range(0, 6)
        .mapToObj(
            posicao ->
                DynamicTest.dynamicTest(
                    "campo na posicao " + posicao,
                    () -> {
                      Object[] campos = {
                        ID, ID_PACIENTE, ID_UPA, ID_EQUIPAMENTO, CRIADO_EM, INICIO_EXAME
                      };
                      campos[posicao] = null;

                      assertThatThrownBy(
                              () ->
                                  criar(
                                      (UUID) campos[0],
                                      (UUID) campos[1],
                                      (UUID) campos[2],
                                      (UUID) campos[3],
                                      (Instant) campos[4],
                                      (Instant) campos[5]))
                          .isInstanceOf(NullPointerException.class);
                    }));
  }

  @Test
  void toStringDeveMostrarSoOIdDoAgendamento() {
    String texto =
        criar(ID, ID_PACIENTE, ID_UPA, ID_EQUIPAMENTO, CRIADO_EM, INICIO_EXAME).toString();

    assertThat(texto).contains(ID.toString());
    assertThat(texto)
        .doesNotContain(ID_PACIENTE.toString())
        .doesNotContain(INICIO_EXAME.toString())
        .doesNotContain(CRIADO_EM.toString());
  }
}
