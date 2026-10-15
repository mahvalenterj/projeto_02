package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.AvisoPacienteRepository;
import br.gov.saude.upa.domain.AvisoPaciente;
import br.gov.saude.upa.domain.MotivoCancelamento;
import br.gov.saude.upa.domain.ResultadoNotificacao;
import br.gov.saude.upa.domain.StatusAviso;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AvisoPacienteRepositoryTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);

  @Autowired private AvisoPacienteRepository repositorio;
  @Autowired private JdbcTemplate jdbc;

  private static AvisoPaciente reserva(UUID evento, UUID agendamento) {
    return AvisoPaciente.reservar(evento, agendamento, T0);
  }

  private long linhas(UUID evento, UUID agendamento) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM aviso_paciente WHERE evento_id = ? AND agendamento_id = ?",
        Long.class,
        evento,
        agendamento);
  }

  @Test
  void deveReservarUmaSoVezQuandoVariasExecucoesDisputamOMesmoAgendamento() throws Exception {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();

    List<Boolean> resultados =
        Disputa.largarJuntas(8, i -> repositorio.reservar(reserva(evento, agendamento)));

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
    assertThat(linhas(evento, agendamento)).isEqualTo(1);
  }

  @Test
  void deveReassumirReservaAbandonadaComUmSoVencedor() throws Exception {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    repositorio.reservar(reserva(evento, agendamento));

    List<Boolean> resultados =
        Disputa.largarJuntas(
            8,
            i ->
                repositorio.reassumir(
                    evento, agendamento, T0, T0.plus(10 + i, ChronoUnit.MINUTES)));

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
    AvisoPaciente gravado = repositorio.buscarPorEvento(evento).get(agendamento);
    assertThat(gravado.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(gravado.reservadoEm()).isAfterOrEqualTo(T0.plus(10, ChronoUnit.MINUTES));
    assertThat(gravado.atualizadoEm()).isEqualTo(gravado.reservadoEm());
  }

  @Test
  void naoDeveReassumirQuandoOReservadoEmMudou() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    repositorio.reservar(reserva(evento, agendamento));

    boolean reassumiu =
        repositorio.reassumir(evento, agendamento, T0.minusSeconds(1), T0.plusSeconds(600));

    assertThat(reassumiu).isFalse();
    assertThat(repositorio.buscarPorEvento(evento).get(agendamento).reservadoEm()).isEqualTo(T0);
  }

  @Test
  void naoDeveReassumirQuandoOAvisoJaTemDesfecho() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    AvisoPaciente aviso = reserva(evento, agendamento);
    repositorio.reservar(aviso);
    aviso.aplicar(new ResultadoNotificacao.Enviada(), T1);
    assertThat(repositorio.registrarDesfecho(aviso)).isTrue();

    boolean reassumiu = repositorio.reassumir(evento, agendamento, T0, T0.plusSeconds(600));

    assertThat(reassumiu).isFalse();
    assertThat(repositorio.buscarPorEvento(evento).get(agendamento).status())
        .isEqualTo(StatusAviso.CONFIRMADO);
  }

  @Test
  void naoDeveReassumirQuandoNaoHouverReserva() {
    assertThat(repositorio.reassumir(UUID.randomUUID(), UUID.randomUUID(), T0, T1)).isFalse();
  }

  @Test
  void deveRegistrarDesfechoDeAvisoPendente() {
    UUID evento = UUID.randomUUID();
    UUID confirmado = UUID.randomUUID();
    UUID cancelado = UUID.randomUUID();
    AvisoPaciente avisoConfirmado = reserva(evento, confirmado);
    AvisoPaciente avisoCancelado = reserva(evento, cancelado);
    repositorio.reservar(avisoConfirmado);
    repositorio.reservar(avisoCancelado);
    avisoConfirmado.aplicar(new ResultadoNotificacao.Enviada(), T1);
    avisoCancelado.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO), T1);

    assertThat(repositorio.registrarDesfecho(avisoConfirmado)).isTrue();
    assertThat(repositorio.registrarDesfecho(avisoCancelado)).isTrue();

    Map<UUID, AvisoPaciente> lidos = repositorio.buscarPorEvento(evento);
    assertThat(lidos.get(confirmado).status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(lidos.get(confirmado).atualizadoEm()).isEqualTo(T1);
    assertThat(lidos.get(cancelado).status()).isEqualTo(StatusAviso.CANCELADO);
    assertThat(lidos.get(cancelado).motivoCancelamento())
        .contains(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);
  }

  @Test
  void naoDeveSobrescreverDesfechoJaGravado() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    AvisoPaciente confirmado = reserva(evento, agendamento);
    repositorio.reservar(confirmado);
    confirmado.aplicar(new ResultadoNotificacao.Enviada(), T1);
    assertThat(repositorio.registrarDesfecho(confirmado)).isTrue();

    AvisoPaciente outraExecucao = reserva(evento, agendamento);
    outraExecucao.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO),
        T1.plusSeconds(5));

    assertThat(repositorio.registrarDesfecho(outraExecucao)).isFalse();
    AvisoPaciente gravado = repositorio.buscarPorEvento(evento).get(agendamento);
    assertThat(gravado.status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(gravado.motivoCancelamento()).isEmpty();
  }

  @Test
  void naoDeveRegistrarDesfechoQuandoNaoHouverReserva() {
    AvisoPaciente semReserva = reserva(UUID.randomUUID(), UUID.randomUUID());
    semReserva.aplicar(new ResultadoNotificacao.Enviada(), T1);

    assertThat(repositorio.registrarDesfecho(semReserva)).isFalse();
  }

  @Test
  void deveGravarELerCadaStatusDoAviso() {
    UUID evento = UUID.randomUUID();
    UUID pendente = UUID.randomUUID();
    UUID confirmado = UUID.randomUUID();
    UUID cancelado = UUID.randomUUID();
    AvisoPaciente avisoConfirmado = reserva(evento, confirmado);
    AvisoPaciente avisoCancelado = reserva(evento, cancelado);
    repositorio.reservar(reserva(evento, pendente));
    repositorio.reservar(avisoConfirmado);
    repositorio.reservar(avisoCancelado);
    avisoConfirmado.aplicar(new ResultadoNotificacao.Enviada(), T1);
    avisoCancelado.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO), T1);
    repositorio.registrarDesfecho(avisoConfirmado);
    repositorio.registrarDesfecho(avisoCancelado);

    Map<UUID, AvisoPaciente> lidos = repositorio.buscarPorEvento(evento);

    assertThat(lidos).containsOnlyKeys(pendente, confirmado, cancelado);
    assertThat(lidos.get(pendente).status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(lidos.get(pendente).motivoCancelamento()).isEmpty();
    assertThat(lidos.get(pendente).reservadoEm()).isEqualTo(T0);
    assertThat(lidos.get(confirmado).status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(lidos.get(cancelado).status()).isEqualTo(StatusAviso.CANCELADO);
    assertThat(lidos.get(cancelado).eventoId()).isEqualTo(evento);
    assertThat(lidos.get(cancelado).agendamentoId()).isEqualTo(cancelado);
  }

  @Test
  void deveBuscarEmLotePorEventoSemMisturarEventos() {
    UUID evento = UUID.randomUUID();
    UUID outroEvento = UUID.randomUUID();
    UUID a1 = UUID.randomUUID();
    UUID a2 = UUID.randomUUID();
    UUID a3 = UUID.randomUUID();
    repositorio.reservar(reserva(evento, a1));
    repositorio.reservar(reserva(evento, a2));
    repositorio.reservar(reserva(outroEvento, a3));

    assertThat(repositorio.buscarPorEvento(evento)).containsOnlyKeys(a1, a2);
    assertThat(repositorio.buscarPorEvento(outroEvento)).containsOnlyKeys(a3);
    assertThat(repositorio.buscarPorEvento(UUID.randomUUID())).isEmpty();
  }

  @Test
  void deveReservarOMesmoAgendamentoEmEventosDiferentes() {
    UUID agendamento = UUID.randomUUID();

    assertThat(repositorio.reservar(reserva(UUID.randomUUID(), agendamento))).isTrue();
    assertThat(repositorio.reservar(reserva(UUID.randomUUID(), agendamento))).isTrue();
  }

  @Test
  void deveTruncarInstantesParaMicrossegundos() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    Instant comNanos = Instant.parse("2026-10-11T15:00:00.123456789Z");

    repositorio.reservar(AvisoPaciente.reservar(evento, agendamento, comNanos));

    AvisoPaciente lido = repositorio.buscarPorEvento(evento).get(agendamento);
    assertThat(lido.reservadoEm()).isEqualTo(comNanos.truncatedTo(ChronoUnit.MICROS));
    assertThat(lido.reservadoEm()).isNotEqualTo(comNanos);
    assertThat(lido.atualizadoEm()).isEqualTo(lido.reservadoEm());
    assertThat(repositorio.reassumir(evento, agendamento, comNanos, T1)).isTrue();
  }

  @Test
  void deveDevolverFalseQuandoAReservaFoiReassumidaDepoisDoInstanteDoDesfecho() {
    UUID evento = UUID.randomUUID();
    UUID agendamento = UUID.randomUUID();
    repositorio.reservar(reserva(evento, agendamento));
    AvisoPaciente execucaoOriginal = reserva(evento, agendamento);
    execucaoOriginal.aplicar(new ResultadoNotificacao.Enviada(), T1);
    Instant reassumidoEm = T1.plusSeconds(30);
    assertThat(repositorio.reassumir(evento, agendamento, T0, reassumidoEm)).isTrue();

    boolean gravou = repositorio.registrarDesfecho(execucaoOriginal);

    assertThat(gravou).isFalse();
    AvisoPaciente gravado = repositorio.buscarPorEvento(evento).get(agendamento);
    assertThat(gravado.status()).isEqualTo(StatusAviso.PENDENTE);
    assertThat(gravado.reservadoEm()).isEqualTo(reassumidoEm);
  }

  @Test
  void deveRejeitarReservarAvisoQueNaoEstaPendente() {
    AvisoPaciente confirmado = reserva(UUID.randomUUID(), UUID.randomUUID());
    confirmado.aplicar(new ResultadoNotificacao.Enviada(), T1);

    assertThatThrownBy(() -> repositorio.reservar(confirmado))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveRejeitarRegistrarDesfechoDeAvisoPendente() {
    AvisoPaciente pendente = reserva(UUID.randomUUID(), UUID.randomUUID());
    repositorio.reservar(pendente);

    assertThatThrownBy(() -> repositorio.registrarDesfecho(pendente))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    assertThatThrownBy(() -> repositorio.buscarPorEvento(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.reservar(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.registrarDesfecho(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.reassumir(null, UUID.randomUUID(), T0, T1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.reassumir(UUID.randomUUID(), null, T0, T1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.reassumir(UUID.randomUUID(), UUID.randomUUID(), null, T1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repositorio.reassumir(UUID.randomUUID(), UUID.randomUUID(), T0, null))
        .isInstanceOf(NullPointerException.class);
  }
}
