package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.OutboxRepository;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Casos de uso de status montados SÓ para teste (a fiação de produção é a fatia 7c). Importada por
 * todas as classes de teste da escrita, para compartilharem um único contexto Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EscritaTestConfiguration {

  public static final Instant AGORA = Instant.parse("2026-10-11T15:00:00Z");

  /** Outbox real que pode ser mandado falhar, para provar a atomicidade com a mudança de status. */
  static class OutboxQueFalha implements OutboxRepository {

    private final OutboxRepository real;
    private final AtomicBoolean falhar = new AtomicBoolean(false);

    OutboxQueFalha(OutboxRepository real) {
      this.real = real;
    }

    void falharNaProximaGravacao(boolean valor) {
      falhar.set(valor);
    }

    @Override
    public void gravar(EquipamentoIndisponivelEvent evento) {
      if (falhar.get()) {
        throw new IllegalStateException("falha simulada ao gravar o outbox");
      }
      real.gravar(evento);
    }
  }

  @Bean
  OutboxQueFalha outboxQueFalha(OutboxRepositoryAdapter real) {
    return new OutboxQueFalha(real);
  }

  @Bean
  MarcarEquipamentoIndisponivel marcarIndisponivelDeTeste(
      UPAPersistencia upas, OutboxQueFalha outbox) {
    return new MarcarEquipamentoIndisponivel(upas, outbox, Clock.fixed(AGORA, ZoneOffset.UTC));
  }

  @Bean
  MarcarEquipamentoDisponivel marcarDisponivelDeTeste(UPAPersistencia upas) {
    return new MarcarEquipamentoDisponivel(upas);
  }
}
