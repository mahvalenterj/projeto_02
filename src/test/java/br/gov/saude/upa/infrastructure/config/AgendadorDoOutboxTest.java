package br.gov.saude.upa.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.ResumoDoDespacho;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Unitário, sem Spring: o agendador nunca morre, nem quando o despachante lança. */
class AgendadorDoOutboxTest {

  private static final String TELEFONE_ISCA = "+5521900000001";

  private final DespachanteDoOutbox despachante = mock(DespachanteDoOutbox.class);
  private final AgendadorDoOutbox agendador = new AgendadorDoOutbox(despachante);
  private final Logger logger = (Logger) LoggerFactory.getLogger(AgendadorDoOutbox.class);
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void capturarLog() {
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void soltarLog() {
    logger.detachAppender(log);
  }

  @Test
  void deveChamarODespachanteACadaRodada() {
    when(despachante.despachar()).thenReturn(new ResumoDoDespacho(0, 0, 0, 0));

    agendador.despachar();
    agendador.despachar();

    verify(despachante, times(2)).despachar();
    assertThat(log.list).isEmpty();
  }

  @Test
  void deveCapturarOErroDoDespachanteELogarSoAClasse() {
    when(despachante.despachar())
        .thenThrow(new IllegalStateException("falhou para o telefone " + TELEFONE_ISCA));

    assertThatCode(agendador::despachar).doesNotThrowAnyException();

    List<ILoggingEvent> eventos = log.list;
    assertThat(eventos).hasSize(1);
    ILoggingEvent evento = eventos.get(0);
    assertThat(evento.getLevel()).isEqualTo(Level.ERROR);
    assertThat(evento.getFormattedMessage()).contains("IllegalStateException");
    assertThat(evento.getFormattedMessage()).doesNotContain(TELEFONE_ISCA);
    assertThat(evento.getThrowableProxy()).isNull();
  }

  @Test
  void deveLogarSoAClasseERelancarUmErrorSemEngoli() {
    Error erro = new StackOverflowError("falhou para o telefone " + TELEFONE_ISCA);
    when(despachante.despachar()).thenThrow(erro);

    assertThatThrownBy(agendador::despachar).isSameAs(erro);

    assertThat(log.list).hasSize(1);
    ILoggingEvent evento = log.list.get(0);
    assertThat(evento.getLevel()).isEqualTo(Level.ERROR);
    assertThat(evento.getFormattedMessage()).contains("StackOverflowError");
    assertThat(evento.getFormattedMessage()).doesNotContain(TELEFONE_ISCA);
    assertThat(evento.getThrowableProxy()).isNull();
  }

  @Test
  void deveRodarDeNovoNaChamadaSeguinteDepoisDeUmaFalha() {
    when(despachante.despachar())
        .thenThrow(new IllegalStateException("x"))
        .thenReturn(new ResumoDoDespacho(1, 1, 0, 0));

    agendador.despachar();
    agendador.despachar();

    verify(despachante, times(2)).despachar();
  }
}
