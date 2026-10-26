package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.application.AlertasOperacionais;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Alertas operacionais como log estruturado em ERROR, com o marcador fixo {@code
 * ALERTA_OPERACIONAL} para o monitoramento filtrar. Só ids e o nome da classe do erro.
 */
class AlertasOperacionaisSlf4j implements AlertasOperacionais {

  private static final Logger LOG = LoggerFactory.getLogger(AlertasOperacionaisSlf4j.class);
  private static final Marker MARCADOR = MarkerFactory.getMarker("ALERTA_OPERACIONAL");

  @Override
  public void avisoIndeterminado(UUID eventoId, UUID agendamentoId) {
    LOG.error(
        MARCADOR, "avisoIndeterminado: eventoId={} agendamentoId={}", eventoId, agendamentoId);
  }

  @Override
  public void falhaInesperada(UUID eventoId, UUID agendamentoId, String tipoDoErro) {
    LOG.error(
        MARCADOR,
        "falhaInesperada: eventoId={} agendamentoId={} tipoDoErro={}",
        eventoId,
        agendamentoId,
        tipoDoErro);
  }

  @Override
  public void dadoInconsistente(UUID eventoId, UUID agendamentoId) {
    LOG.error(MARCADOR, "dadoInconsistente: eventoId={} agendamentoId={}", eventoId, agendamentoId);
  }
}
