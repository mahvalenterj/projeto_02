package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** As regras do schema (V1) valem mesmo para quem escreve direto no banco. */
class MigrationTest extends PostgresIntegrationTest {

  private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-10-11T15:00:00Z"));
  private static final Timestamp T1 = Timestamp.from(Instant.parse("2026-10-11T15:01:00Z"));

  @Autowired private JdbcTemplate jdbc;

  private void inserirAviso(
      UUID evento,
      UUID agendamento,
      String status,
      String motivo,
      Timestamp reservado,
      Timestamp atualizado) {
    jdbc.update(
        "INSERT INTO aviso_paciente (evento_id, agendamento_id, status, motivo_cancelamento,"
            + " reservado_em, atualizado_em) VALUES (?, ?, ?, ?, ?, ?)",
        evento,
        agendamento,
        status,
        motivo,
        reservado,
        atualizado);
  }

  @Test
  void deveAceitarOsTresStatusValidos() {
    assertThatCode(
            () -> {
              inserirAviso(UUID.randomUUID(), UUID.randomUUID(), "PENDENTE", null, T0, T0);
              inserirAviso(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMADO", null, T0, T1);
              inserirAviso(
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "CANCELADO",
                  "EQUIPAMENTO_RESTABELECIDO",
                  T0,
                  T1);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void deveRejeitarStatusInvalido() {
    assertThatThrownBy(
            () -> inserirAviso(UUID.randomUUID(), UUID.randomUUID(), "ENVIADO", null, T0, T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deveExigirMotivoSoQuandoCancelado() {
    assertThatThrownBy(
            () -> inserirAviso(UUID.randomUUID(), UUID.randomUUID(), "CANCELADO", null, T0, T1))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                inserirAviso(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CONFIRMADO",
                    "EQUIPAMENTO_RESTABELECIDO",
                    T0,
                    T1))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deveRejeitarAtualizadoEmAnteriorAoReservadoEm() {
    assertThatThrownBy(
            () -> inserirAviso(UUID.randomUUID(), UUID.randomUUID(), "PENDENTE", null, T1, T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deveRejeitarChaveDuplicadaEmAvisoERevisaoManual() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    inserirAviso(evento, agendamento, "PENDENTE", null, T0, T0);
    jdbc.update(
        "INSERT INTO revisao_manual (evento_id, agendamento_id, motivo, registrado_em)"
            + " VALUES (?, ?, 'SEM_CONTATO', ?)",
        evento,
        agendamento,
        T0);

    assertThatThrownBy(() -> inserirAviso(evento, agendamento, "PENDENTE", null, T0, T0))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO revisao_manual (evento_id, agendamento_id, motivo, registrado_em)"
                        + " VALUES (?, ?, 'SEM_CONTATO', ?)",
                    evento,
                    agendamento,
                    T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
