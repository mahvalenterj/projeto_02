package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResultadoNotificacaoTest {

  @Test
  void deveGuardarOMotivoDaCancelamento() {
    var resultado =
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);

    assertThat(resultado.motivo()).isEqualTo(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);
  }

  @Test
  void deveGuardarOMotivoDaRevisaoManual() {
    var resultado = new ResultadoNotificacao.RevisaoManual(MotivoRevisaoManual.SEM_CONTATO);

    assertThat(resultado.motivo()).isEqualTo(MotivoRevisaoManual.SEM_CONTATO);
  }

  @Test
  void deveRejeitarMotivoNulo() {
    assertThatThrownBy(() -> new ResultadoNotificacao.Cancelada(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("motivo");
    assertThatThrownBy(() -> new ResultadoNotificacao.RevisaoManual(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("motivo");
  }

  @Test
  void resultadosSemDadosDevemSerIguais() {
    assertThat(new ResultadoNotificacao.Enviada()).isEqualTo(new ResultadoNotificacao.Enviada());
    assertThat(new ResultadoNotificacao.Indeterminada())
        .isEqualTo(new ResultadoNotificacao.Indeterminada());
  }
}
