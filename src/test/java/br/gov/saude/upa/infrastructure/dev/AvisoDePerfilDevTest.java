package br.gov.saude.upa.infrastructure.dev;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.DetectorDeDadoSensivel;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

class AvisoDePerfilDevTest {

  @Test
  void deveAvisarEmWarnQueOPerfilDevEstaAtivo() {
    DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();
    captura.ligarNaRaiz();
    try {
      new AvisoDePerfilDev().avisar();
    } finally {
      captura.desligar();
    }

    assertThat(captura.eventos())
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.WARN);
              assertThat(e.getFormattedMessage())
                  .contains(
                      "PERFIL DEV ATIVO: endpoints /dev e chave de desenvolvimento. Nunca use em"
                          + " ambiente real.");
            });
  }
}
