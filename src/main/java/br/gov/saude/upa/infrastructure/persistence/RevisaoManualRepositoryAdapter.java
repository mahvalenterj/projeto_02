package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.RevisaoManualRepository;
import br.gov.saude.upa.domain.ItemDeRevisaoManual;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@code @Component}, pelo mesmo motivo do {@code AvisoPacienteRepositoryAdapter}. */
@Component
class RevisaoManualRepositoryAdapter implements RevisaoManualRepository {

  private final RevisaoManualJpaRepository jpa;

  RevisaoManualRepositoryAdapter(RevisaoManualJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public boolean registrar(ItemDeRevisaoManual item) {
    Objects.requireNonNull(item, "item");
    return jpa.inserirSeAusente(
            item.eventoId(),
            item.agendamentoId(),
            item.motivo().name(),
            item.registradoEm().truncatedTo(ChronoUnit.MICROS))
        == 1;
  }

  @Override
  public Set<UUID> agendamentosEmRevisao(UUID eventoId) {
    Objects.requireNonNull(eventoId, "eventoId");
    return Set.copyOf(jpa.agendamentosDoEvento(eventoId));
  }
}
