package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.application.AlertasDoOutbox;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Alerta do despachante como log em ERROR com o marcador fixo {@code ALERTA_OUTBOX}. Só o id do
 * evento, a contagem de tentativas e o nome da classe do erro.
 */
class AlertasDoOutboxSlf4j implements AlertasDoOutbox {

  private static final Logger LOG = LoggerFactory.getLogger(AlertasDoOutboxSlf4j.class);
  private static final Marker MARCADOR = MarkerFactory.getMarker("ALERTA_OUTBOX");

  @Override
  public void eventoEsgotado(UUID eventoId, int tentativas, String tipoDoUltimoErro) {
    LOG.error(
        MARCADOR,
        "eventoEsgotado: eventoId={} tentativas={} tipoDoUltimoErro={}",
        eventoId,
        tentativas,
        tipoDoUltimoErro);
  }
}
