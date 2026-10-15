package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.ItemDeRevisaoManual;
import java.util.Set;
import java.util.UUID;

/** Porta de saída da fila de revisão manual (ADR 0001, item 5). */
public interface RevisaoManualRepository {

  /** Grava o item. {@code false} se o par {@code (evento, agendamento)} já estava registrado. */
  boolean registrar(ItemDeRevisaoManual item);

  /** Ids dos agendamentos do evento que já estão em revisão manual, numa só consulta. */
  Set<UUID> agendamentosEmRevisao(UUID eventoId);
}
