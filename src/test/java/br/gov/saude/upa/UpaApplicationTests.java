package br.gov.saude.upa;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.application.AlertasDoOutbox;
import br.gov.saude.upa.application.AlertasOperacionais;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import br.gov.saude.upa.domain.PoliticaDeReserva;
import br.gov.saude.upa.domain.SelecaoDeAvisos;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class UpaApplicationTests extends PostgresIntegrationTest {

  @Autowired private ApplicationContext contexto;

  @Test
  void deveCarregarContextoDaAplicacaoComTodosOsBeansDoAvisoLigados() {
    assertThat(contexto.getBeansOfType(Clock.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(SelecaoDeAvisos.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(PoliticaDeReserva.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(NotificarPacientesAfetados.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(MarcarEquipamentoIndisponivel.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(MarcarEquipamentoDisponivel.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(DespachanteDoOutbox.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(AlertasOperacionais.class)).hasSize(1);
    assertThat(contexto.getBeansOfType(AlertasDoOutbox.class)).hasSize(1);
  }

  @Test
  void naoDeveTerOAgendadorLigadoNosTestesDeIntegracao() {
    assertThat(
            contexto.getBeanNamesForType(
                br.gov.saude.upa.infrastructure.config.AgendadorDoOutbox.class))
        .isEmpty();
  }
}
