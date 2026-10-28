package br.gov.saude.upa.infrastructure.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Aviso bem visível, no fim da subida, de que o perfil de desenvolvimento está ligado. */
class AvisoDePerfilDev {

  private static final Logger LOG = LoggerFactory.getLogger(AvisoDePerfilDev.class);

  static final String MENSAGEM =
      "PERFIL DEV ATIVO: endpoints /dev e chave de desenvolvimento. Nunca use em ambiente real.";

  @EventListener(ApplicationReadyEvent.class)
  void avisar() {
    LOG.warn("==================================================================");
    LOG.warn(MENSAGEM);
    LOG.warn("==================================================================");
  }
}
