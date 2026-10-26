package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.application.DespachanteDoOutbox;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara o despachante do outbox em intervalo fixo. O despachante relança no fim da rodada o
 * primeiro erro de evento; aqui ele é capturado para que o agendamento nunca morra (uma exceção
 * escapando de um {@code @Scheduled} de atraso fixo não para a repetição, mas aqui a falha vira log
 * explícito). O evento falho já foi devolvido à fila pelo despachante. Só o nome da classe do erro
 * vai para o log: a mensagem poderia carregar dado de paciente.
 */
public class AgendadorDoOutbox {

  private static final Logger LOG = LoggerFactory.getLogger(AgendadorDoOutbox.class);

  private final DespachanteDoOutbox despachante;

  public AgendadorDoOutbox(DespachanteDoOutbox despachante) {
    this.despachante = Objects.requireNonNull(despachante, "despachante");
  }

  @Scheduled(fixedDelayString = "${upa.outbox.intervalo}")
  public void despachar() {
    try {
      despachante.despachar();
    } catch (RuntimeException e) {
      LOG.error("Rodada do despachante do outbox falhou: tipoDoErro={}", e.getClass().getName());
    } catch (Error e) {
      // Erro da JVM não se engole: loga só a classe e relança para quem supervisiona.
      LOG.error("Rodada do despachante do outbox falhou: tipoDoErro={}", e.getClass().getName());
      throw e;
    }
  }
}
