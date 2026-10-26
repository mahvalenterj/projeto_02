package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Os alertas levam só ids, contagem e o nome da classe do erro, em ERROR com marcador fixo. */
class AlertasSlf4jTest {

  private static final UUID EVENTO = UUID.fromString("00000000-0000-0000-0000-00000000e001");
  private static final UUID AGENDAMENTO = UUID.fromString("00000000-0000-0000-0000-00000000a001");
  private static final String TELEFONE_ISCA = "+5521900000001";
  private static final String NOME_ISCA = "Fulana Sintetica";

  private final Logger loggerOperacional =
      (Logger) LoggerFactory.getLogger(AlertasOperacionaisSlf4j.class);
  private final Logger loggerOutbox = (Logger) LoggerFactory.getLogger(AlertasDoOutboxSlf4j.class);
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void capturarLog() {
    log.start();
    loggerOperacional.addAppender(log);
    loggerOutbox.addAppender(log);
  }

  @AfterEach
  void soltarLog() {
    loggerOperacional.detachAppender(log);
    loggerOutbox.detachAppender(log);
  }

  private ILoggingEvent unicoEvento() {
    assertThat(log.list).hasSize(1);
    ILoggingEvent evento = log.list.get(0);
    assertThat(evento.getLevel()).isEqualTo(Level.ERROR);
    assertThat(evento.getThrowableProxy()).isNull();
    return evento;
  }

  private static void semIsca(ILoggingEvent evento) {
    assertThat(evento.getFormattedMessage())
        .doesNotContain(TELEFONE_ISCA)
        .doesNotContain(NOME_ISCA);
  }

  @Test
  void deveAlertarAvisoIndeterminadoComSoOsIds() {
    new AlertasOperacionaisSlf4j().avisoIndeterminado(EVENTO, AGENDAMENTO);

    ILoggingEvent evento = unicoEvento();
    assertThat(evento.getMarkerList()).extracting(m -> m.getName()).contains("ALERTA_OPERACIONAL");
    assertThat(evento.getFormattedMessage())
        .contains(EVENTO.toString(), AGENDAMENTO.toString())
        .contains("avisoIndeterminado");
  }

  @Test
  void deveAlertarFalhaInesperadaComIdsEClasseDoErroSemDadoDePaciente() {
    RuntimeException erro =
        new IllegalStateException("paciente " + NOME_ISCA + " telefone " + TELEFONE_ISCA);

    new AlertasOperacionaisSlf4j().falhaInesperada(EVENTO, AGENDAMENTO, erro.getClass().getName());

    ILoggingEvent evento = unicoEvento();
    assertThat(evento.getMarkerList()).extracting(m -> m.getName()).contains("ALERTA_OPERACIONAL");
    assertThat(evento.getFormattedMessage())
        .contains(EVENTO.toString(), AGENDAMENTO.toString(), "java.lang.IllegalStateException");
    semIsca(evento);
  }

  @Test
  void deveAlertarDadoInconsistenteComSoOsIds() {
    new AlertasOperacionaisSlf4j().dadoInconsistente(EVENTO, AGENDAMENTO);

    ILoggingEvent evento = unicoEvento();
    assertThat(evento.getMarkerList()).extracting(m -> m.getName()).contains("ALERTA_OPERACIONAL");
    assertThat(evento.getFormattedMessage())
        .contains(EVENTO.toString(), AGENDAMENTO.toString())
        .contains("dadoInconsistente");
  }

  @Test
  void deveAlertarEventoEsgotadoComIdTentativasEClasseDoErro() {
    new AlertasDoOutboxSlf4j().eventoEsgotado(EVENTO, 5, "FalhasInesperadasNoLote");

    ILoggingEvent evento = unicoEvento();
    assertThat(evento.getMarkerList()).extracting(m -> m.getName()).contains("ALERTA_OUTBOX");
    assertThat(evento.getFormattedMessage())
        .contains(EVENTO.toString(), "5", "FalhasInesperadasNoLote");
    semIsca(evento);
  }
}
