package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DespachanteDoOutboxTest {

  private static final Instant AGORA = Instant.parse("2026-10-11T15:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(150);
  private static final Duration ATRASO = Duration.ofSeconds(130);
  private static final int LOTE = 7;
  private static final int MAXIMO = 3;

  private final FilaDoOutbox fila = mock(FilaDoOutbox.class);
  private final NotificarPacientesAfetados lote = mock(NotificarPacientesAfetados.class);
  private final AlertasDoOutbox alertas = mock(AlertasDoOutbox.class);
  private final DespachanteDoOutbox despachante =
      new DespachanteDoOutbox(
          fila, lote, alertas, Clock.fixed(AGORA, ZoneOffset.UTC), LOTE, LEASE, ATRASO, MAXIMO);

  @BeforeEach
  void escritasGanhamPorPadrao() {
    when(fila.marcarProcessado(any(), any())).thenReturn(true);
    when(fila.devolver(any(), any(), anyString())).thenReturn(true);
    when(fila.esgotar(any(), anyString())).thenReturn(true);
  }

  private static EventoReivindicado reivindicado(int tentativas) {
    return new EventoReivindicado(
        new EquipamentoIndisponivelEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AGORA.minusSeconds(5)),
        tentativas);
  }

  private static Reivindicacao com(EventoReivindicado... eventos) {
    return new Reivindicacao(List.of(eventos), List.of());
  }

  /** A fila entrega um por vez: cada chamada devolve o próximo e, no fim, vazio. */
  private void fila(Reivindicacao primeira, Reivindicacao... seguintes) {
    when(fila.reivindicar(AGORA, LEASE, 1))
        .thenReturn(primeira, seguintes)
        .thenReturn(Reivindicacao.vazia());
  }

  private static ResumoDoLote resumo(int confirmados, int falhas) {
    return new ResumoDoLote(confirmados + falhas, confirmados, 0, 0, 0, 0, falhas);
  }

  @Test
  void deveReivindicarUmEventoPorVezComOLeaseConfigurado() {
    when(fila.reivindicar(AGORA, LEASE, 1)).thenReturn(Reivindicacao.vazia());

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(0, 0, 0, 0));
    verify(fila, times(1)).reivindicar(AGORA, LEASE, 1);
    verifyNoInteractions(lote, alertas);
  }

  @Test
  void deveParaNoTamanhoDoLoteMesmoComMaisEventosNaFila() {
    when(fila.reivindicar(AGORA, LEASE, 1)).thenAnswer(i -> com(reivindicado(1)));
    when(lote.executar(any())).thenReturn(resumo(1, 0));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(LOTE, LOTE, 0, 0));
    verify(fila, times(LOTE)).reivindicar(AGORA, LEASE, 1);
  }

  @Test
  void deveMarcarProcessadoQuandoOLoteNaoTemFalhasInesperadas() {
    EventoReivindicado evento = reivindicado(1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(3, 0));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    verify(fila).marcarProcessado(evento, AGORA);
    verify(fila, never()).devolver(any(), any(), anyString());
    verifyNoInteractions(alertas);
  }

  @Test
  void deveMarcarProcessadoMesmoComIndeterminadosPoisOLotePendenteJaTemAlerta() {
    EventoReivindicado evento = reivindicado(1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(new ResumoDoLote(2, 0, 0, 0, 2, 0, 0));

    despachante.despachar();

    verify(fila).marcarProcessado(evento, AGORA);
  }

  @Test
  void deveDevolverComAtrasoDe130sQuandoOLoteTemFalhaInesperada() {
    EventoReivindicado evento = reivindicado(1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(2, 1));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 1, 0));
    verify(fila).devolver(evento, AGORA.plusSeconds(130), "FalhasInesperadasNoLote");
    verify(fila, never()).marcarProcessado(any(), any());
    verifyNoInteractions(alertas);
  }

  @Test
  void deveDevolverRegistrandoSoONomeDaClasseQuandoOCasoDeUsoLanca() {
    EventoReivindicado evento = reivindicado(2);
    fila(com(evento));
    when(lote.executar(evento.evento()))
        .thenThrow(new IllegalStateException("mensagem com Maria 11999990000"));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 1, 0));
    verify(fila).devolver(evento, AGORA.plusSeconds(130), "IllegalStateException");
  }

  @Test
  void deveContinuarNosDemaisEventosQuandoUmFalha() {
    EventoReivindicado ruim = reivindicado(1);
    EventoReivindicado bom = reivindicado(1);
    fila(com(ruim), com(bom));
    when(lote.executar(ruim.evento())).thenThrow(new LoteInterrompidoException(UUID.randomUUID()));
    when(lote.executar(bom.evento())).thenReturn(resumo(1, 0));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(2, 1, 1, 0));
    verify(fila).devolver(ruim, AGORA.plusSeconds(130), "LoteInterrompidoException");
    verify(fila).marcarProcessado(bom, AGORA);
  }

  @Test
  void deveProcessarOsDemaisEPropagarOErroQuandoAFilaLancaAoMarcarUmEvento() {
    EventoReivindicado quebrado = reivindicado(1);
    EventoReivindicado bom = reivindicado(1);
    fila(com(quebrado), com(bom));
    when(lote.executar(any())).thenReturn(resumo(1, 0));
    when(fila.marcarProcessado(quebrado, AGORA)).thenThrow(new IllegalStateException("banco"));

    assertThatThrownBy(despachante::despachar)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("banco");

    verify(lote).executar(bom.evento());
    verify(fila).marcarProcessado(bom, AGORA);
  }

  @Test
  void deveEsgotarEAlertarQuandoFalhaNaUltimaTentativaPermitida() {
    EventoReivindicado evento = reivindicado(MAXIMO);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(0, 1));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 1));
    verify(fila).esgotar(evento, "FalhasInesperadasNoLote");
    verify(alertas).eventoEsgotado(evento.evento().eventoId(), MAXIMO, "FalhasInesperadasNoLote");
    verify(fila, never()).devolver(any(), any(), anyString());
  }

  @Test
  void deveDevolverQuandoFalhaAntesDaUltimaTentativaPermitida() {
    EventoReivindicado evento = reivindicado(MAXIMO - 1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(0, 1));

    despachante.despachar();

    verify(fila).devolver(evento, AGORA.plusSeconds(130), "FalhasInesperadasNoLote");
    verifyNoInteractions(alertas);
  }

  @Test
  void deveEsgotarSemExecutarQuandoAsTentativasJaPassaramDoMaximo() {
    EventoReivindicado evento = reivindicado(MAXIMO + 1);
    fila(com(evento));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 1));
    verifyNoInteractions(lote);
    verify(fila).esgotar(evento, "LeaseExpiradoVariasVezes");
    verify(alertas)
        .eventoEsgotado(evento.evento().eventoId(), MAXIMO + 1, "LeaseExpiradoVariasVezes");
  }

  @Test
  void naoDeveContarNemAlertarQuandoPerdeuOLeaseAoMarcarProcessado() {
    EventoReivindicado evento = reivindicado(1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(1, 0));
    when(fila.marcarProcessado(evento, AGORA)).thenReturn(false);

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 0));
    verifyNoInteractions(alertas);
  }

  @Test
  void naoDeveContarQuandoPerdeuOLeaseAoDevolver() {
    EventoReivindicado evento = reivindicado(1);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(0, 1));
    when(fila.devolver(any(), any(), anyString())).thenReturn(false);

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 0));
  }

  @Test
  void naoDeveAlertarNemContarEsgotadoQuandoPerdeuOLeaseAoEsgotar() {
    EventoReivindicado evento = reivindicado(MAXIMO);
    fila(com(evento));
    when(lote.executar(evento.evento())).thenReturn(resumo(0, 1));
    when(fila.esgotar(any(), anyString())).thenReturn(false);

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 0));
    verifyNoInteractions(alertas);
  }

  @Test
  void deveAlertarELimitarOLoteQuandoALinhaDoOutboxEstaIlegivel() {
    UUID ilegivel = UUID.randomUUID();
    EventoReivindicado bom = reivindicado(1);
    fila(new Reivindicacao(List.of(), List.of(new EventoIlegivel(ilegivel, 2))), com(bom));
    when(lote.executar(bom.evento())).thenReturn(resumo(1, 0));

    ResumoDoDespacho resultado = despachante.despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(2, 1, 0, 1));
    verify(alertas).eventoEsgotado(ilegivel, 2, "PayloadIlegivel");
    verify(lote, times(1)).executar(any());
  }

  @Test
  void deveAnexarAFalhaAcumuladaQuandoUmaReivindicacaoPosteriorLanca() {
    EventoReivindicado quebrado = reivindicado(1);
    IllegalStateException erroDoEvento = new IllegalStateException("banco ao marcar");
    IllegalArgumentException erroDaFila = new IllegalArgumentException("fila fora");
    when(fila.reivindicar(AGORA, LEASE, 1)).thenReturn(com(quebrado)).thenThrow(erroDaFila);
    when(lote.executar(any())).thenReturn(resumo(1, 0));
    when(fila.marcarProcessado(quebrado, AGORA)).thenThrow(erroDoEvento);

    assertThatThrownBy(despachante::despachar)
        .isSameAs(erroDaFila)
        .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(erroDoEvento));
  }

  @Test
  void deveLogarSoIdTentativasETipoDoErroQuandoOCasoDeUsoLanca() {
    ListAppender<ILoggingEvent> log = capturarLogs();
    try {
      EventoReivindicado evento = reivindicado(2);
      fila(com(evento));
      when(lote.executar(evento.evento()))
          .thenThrow(new IllegalStateException("ISCA Maria Silva 11999990000 tomografia"));

      despachante.despachar();

      assertThat(log.list).hasSize(1);
      ILoggingEvent registro = log.list.get(0);
      assertThat(registro.getLevel()).isEqualTo(Level.WARN);
      assertThat(registro.getFormattedMessage())
          .contains(
              evento.evento().eventoId().toString(),
              "tentativas=2",
              "tipoDoErro=IllegalStateException")
          .doesNotContain("ISCA", "Maria", "11999990000");
      assertThat(registro.getThrowableProxy()).isNull();
    } finally {
      ((Logger) LoggerFactory.getLogger(DespachanteDoOutbox.class)).detachAppender(log);
    }
  }

  @Test
  void deveLogarQuandoOLoteTerminaComFalhasInesperadas() {
    ListAppender<ILoggingEvent> log = capturarLogs();
    try {
      EventoReivindicado evento = reivindicado(1);
      fila(com(evento));
      when(lote.executar(evento.evento())).thenReturn(resumo(1, 2));

      despachante.despachar();

      assertThat(log.list).hasSize(1);
      assertThat(log.list.get(0).getLevel()).isEqualTo(Level.WARN);
      assertThat(log.list.get(0).getFormattedMessage())
          .contains(evento.evento().eventoId().toString(), "FalhasInesperadasNoLote");
    } finally {
      ((Logger) LoggerFactory.getLogger(DespachanteDoOutbox.class)).detachAppender(log);
    }
  }

  private static ListAppender<ILoggingEvent> capturarLogs() {
    ListAppender<ILoggingEvent> log = new ListAppender<>();
    log.start();
    ((Logger) LoggerFactory.getLogger(DespachanteDoOutbox.class)).addAppender(log);
    return log;
  }

  @Test
  void deveRejeitarParametrosInvalidos() {
    Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
    assertThatThrownBy(
            () -> new DespachanteDoOutbox(fila, lote, alertas, relogio, 0, LEASE, ATRASO, MAXIMO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DespachanteDoOutbox(
                    fila, lote, alertas, relogio, LOTE, Duration.ZERO, ATRASO, MAXIMO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DespachanteDoOutbox(
                    fila, lote, alertas, relogio, LOTE, LEASE, Duration.ofSeconds(-1), MAXIMO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DespachanteDoOutbox(fila, lote, alertas, relogio, LOTE, LEASE, ATRASO, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
