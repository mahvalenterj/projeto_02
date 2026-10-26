package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.application.AgendamentoRepository;
import br.gov.saude.upa.application.AlertasOperacionais;
import br.gov.saude.upa.application.AvisoPacienteRepository;
import br.gov.saude.upa.application.ConsultaDeEquipamento;
import br.gov.saude.upa.application.ContatoDePacienteRepository;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.NotificadorEmail;
import br.gov.saude.upa.application.NotificadorSms;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import br.gov.saude.upa.application.OutboxRepository;
import br.gov.saude.upa.application.RevisaoManualRepository;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.PoliticaDeReserva;
import br.gov.saude.upa.domain.SelecaoDeAvisos;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monta os casos de uso do aviso a partir das portas e dos adapters. Onde há mais de um candidato
 * (testes trazem fakes), o adapter de produção é escolhido pelo nome do bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvisoProperties.class)
class AvisoConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  AlertasOperacionais alertasOperacionais() {
    return new AlertasOperacionaisSlf4j();
  }

  @Bean
  SelecaoDeAvisos selecaoDeAvisos(Clock clock) {
    return new SelecaoDeAvisos(clock);
  }

  @Bean
  PoliticaDeReserva politicaDeReserva() {
    return new PoliticaDeReserva();
  }

  @Bean
  NotificarPacientesAfetados notificarPacientesAfetados(
      UPAPersistencia upas,
      @Qualifier("agendamentoRepositoryAdapter") AgendamentoRepository agendamentos,
      @Qualifier("contatoDePacienteRepositoryAdapter") ContatoDePacienteRepository contatos,
      ConsultaDeEquipamento consulta,
      @Qualifier("notificadorSmsHttp") NotificadorSms sms,
      @Qualifier("notificadorEmailHttp") NotificadorEmail email,
      AvisoPacienteRepository avisos,
      RevisaoManualRepository revisoes,
      @Qualifier("alertasOperacionais") AlertasOperacionais alertas,
      SelecaoDeAvisos selecao,
      PoliticaDeReserva politica,
      Clock clock,
      AvisoProperties p) {
    return new NotificarPacientesAfetados(
        upas,
        agendamentos,
        contatos,
        consulta,
        sms,
        email,
        avisos,
        revisoes,
        alertas,
        selecao,
        politica,
        clock,
        p.tempoLimiteReserva(),
        p.concorrenciaMaxima());
  }

  @Bean
  MarcarEquipamentoIndisponivel marcarEquipamentoIndisponivel(
      UPAPersistencia upas,
      @Qualifier("outboxRepositoryAdapter") OutboxRepository outbox,
      Clock clock) {
    return new MarcarEquipamentoIndisponivel(upas, outbox, clock);
  }

  @Bean
  MarcarEquipamentoDisponivel marcarEquipamentoDisponivel(UPAPersistencia upas) {
    return new MarcarEquipamentoDisponivel(upas);
  }
}
