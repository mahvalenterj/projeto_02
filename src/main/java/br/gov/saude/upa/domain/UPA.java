package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Aggregate Root: todo acesso aos {@link Equipamento} passa por aqui. */
public class UPA {

  private final UUID id;
  private final List<Equipamento> equipamentos;
  private final List<EquipamentoIndisponivelEvent> eventos = new ArrayList<>();

  public UPA(UUID id, List<Equipamento> equipamentos) {
    this.id = Objects.requireNonNull(id, "id");
    this.equipamentos = List.copyOf(Objects.requireNonNull(equipamentos, "equipamentos"));
    rejeitarIdsDuplicados(this.equipamentos);
  }

  public UUID id() {
    return id;
  }

  public List<Equipamento> equipamentos() {
    return equipamentos;
  }

  public List<EquipamentoIndisponivelEvent> eventosRegistrados() {
    return List.copyOf(eventos);
  }

  public void limparEventos() {
    eventos.clear();
  }

  public void marcarEquipamentoIndisponivel(
      UUID idEquipamento, UUID eventoId, Instant momentoFalha) {
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    Equipamento equipamento = buscar(idEquipamento);
    var evento = new EquipamentoIndisponivelEvent(eventoId, idEquipamento, id, momentoFalha);
    equipamento.marcarIndisponivel();
    eventos.add(evento);
  }

  private Equipamento buscar(UUID idEquipamento) {
    return equipamentos.stream()
        .filter(equipamento -> equipamento.id().equals(idEquipamento))
        .findFirst()
        .orElseThrow(() -> new EquipamentoNaoEncontradoException(idEquipamento, id));
  }

  private static void rejeitarIdsDuplicados(List<Equipamento> equipamentos) {
    Set<UUID> vistos = new HashSet<>();
    for (Equipamento equipamento : equipamentos) {
      if (!vistos.add(equipamento.id())) {
        throw new IllegalArgumentException("Equipamento duplicado na UPA: " + equipamento.id());
      }
    }
  }
}
