package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.UPA;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marca o equipamento como indisponível e grava o evento no outbox na MESMA transação: ou os dois
 * acontecem, ou nenhum (spec 001 §5, passo 2).
 */
public class MarcarEquipamentoIndisponivel {

  private final UPAPersistencia upas;
  private final OutboxRepository outbox;
  private final Clock relogio;

  public MarcarEquipamentoIndisponivel(
      UPAPersistencia upas, OutboxRepository outbox, Clock relogio) {
    this.upas = Objects.requireNonNull(upas, "upas");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.relogio = Objects.requireNonNull(relogio, "relogio");
  }

  /**
   * @return o {@code eventoId} do evento gravado; cada marcação gera um novo (CA17)
   */
  @Transactional
  public UUID executar(UUID idUpa, UUID idEquipamento) {
    Objects.requireNonNull(idUpa, "idUpa");
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    UPA upa = upas.buscar(idUpa).orElseThrow(() -> new UpaNaoEncontradaException(idUpa));
    UUID eventoId = UUID.randomUUID();
    Instant momentoFalha = relogio.instant();

    upa.marcarEquipamentoIndisponivel(idEquipamento, eventoId, momentoFalha);
    upas.salvar(upa);
    for (EquipamentoIndisponivelEvent evento : upa.eventosRegistrados()) {
      outbox.gravar(evento);
    }
    upa.limparEventos();
    return eventoId;
  }
}
