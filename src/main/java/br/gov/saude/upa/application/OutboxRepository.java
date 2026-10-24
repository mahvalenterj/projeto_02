package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;

/** Porta de saída que grava o evento no outbox, na transação do chamador. */
public interface OutboxRepository {

  void gravar(EquipamentoIndisponivelEvent evento);
}
