package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.RevisaoManualRepository;
import br.gov.saude.upa.domain.ItemDeRevisaoManual;
import br.gov.saude.upa.domain.MotivoRevisaoManual;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RevisaoManualRepositoryTest extends PostgresIntegrationTest {

  private static final Instant AGORA = Instant.parse("2026-10-11T15:03:00Z");

  @Autowired private RevisaoManualRepository repositorio;
  @Autowired private JdbcTemplate jdbc;

  private static ItemDeRevisaoManual item(UUID evento, UUID agendamento) {
    return new ItemDeRevisaoManual(evento, agendamento, MotivoRevisaoManual.SEM_CONTATO, AGORA);
  }

  private long linhas(UUID evento, UUID agendamento) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM revisao_manual WHERE evento_id = ? AND agendamento_id = ?",
        Long.class,
        evento,
        agendamento);
  }

  @Test
  void deveRegistrarUmaSoVezMesmoQuandoReentregue() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();

    assertThat(repositorio.registrar(item(evento, agendamento))).isTrue();
    assertThat(repositorio.registrar(item(evento, agendamento))).isFalse();

    assertThat(linhas(evento, agendamento)).isEqualTo(1);
  }

  @Test
  void deveRegistrarUmaSoVezQuandoVariasExecucoesDisputamOMesmoAgendamento() throws Exception {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();

    List<Boolean> resultados =
        Disputa.largarJuntas(8, i -> repositorio.registrar(item(evento, agendamento)));

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
    assertThat(linhas(evento, agendamento)).isEqualTo(1);
  }

  @Test
  void deveManterOPrimeiroMotivoQuandoRegistradoDeNovo() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    repositorio.registrar(
        new ItemDeRevisaoManual(evento, agendamento, MotivoRevisaoManual.SEM_CONTATO, AGORA));

    repositorio.registrar(
        new ItemDeRevisaoManual(
            evento, agendamento, MotivoRevisaoManual.CANAIS_ESGOTADOS, AGORA.plusSeconds(60)));

    String motivo =
        jdbc.queryForObject(
            "SELECT motivo FROM revisao_manual WHERE evento_id = ? AND agendamento_id = ?",
            String.class,
            evento,
            agendamento);
    assertThat(motivo).isEqualTo("SEM_CONTATO");
  }

  @Test
  void deveBuscarEmLotePorEventoSemMisturarEventos() {
    UUID evento = UUID.randomUUID();
    UUID outroEvento = UUID.randomUUID();
    UUID a1 = UUID.randomUUID();
    UUID a2 = UUID.randomUUID();
    UUID a3 = UUID.randomUUID();
    repositorio.registrar(item(evento, a1));
    repositorio.registrar(item(evento, a2));
    repositorio.registrar(item(outroEvento, a3));

    assertThat(repositorio.agendamentosEmRevisao(evento)).containsExactlyInAnyOrder(a1, a2);
    assertThat(repositorio.agendamentosEmRevisao(outroEvento)).containsExactly(a3);
    assertThat(repositorio.agendamentosEmRevisao(UUID.randomUUID())).isEmpty();
  }

  @Test
  void deveRegistrarOMesmoAgendamentoEmEventosDiferentes() {
    UUID agendamento = UUID.randomUUID();

    assertThat(repositorio.registrar(item(UUID.randomUUID(), agendamento))).isTrue();
    assertThat(repositorio.registrar(item(UUID.randomUUID(), agendamento))).isTrue();
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    assertThatThrownBy(() -> repositorio.registrar(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.agendamentosEmRevisao(null))
        .isInstanceOf(NullPointerException.class);
  }
}
