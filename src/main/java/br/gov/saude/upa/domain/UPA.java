package br.gov.saude.upa.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Aggregate Root: todo acesso aos {@link Equipamento} passa por aqui. */
public class UPA {

  private final UUID id;
  private final String nome;
  private final List<Equipamento> equipamentos;
  private final Map<TipoEquipamento, EnderecoAlternativo> enderecosAlternativos;
  private final List<EquipamentoIndisponivelEvent> eventos = new ArrayList<>();

  public UPA(
      UUID id,
      String nome,
      List<Equipamento> equipamentos,
      Map<TipoEquipamento, EnderecoAlternativo> enderecosAlternativos) {
    this.id = Objects.requireNonNull(id, "id");
    this.nome = exigirNomeValido(nome);
    this.equipamentos = List.copyOf(Objects.requireNonNull(equipamentos, "equipamentos"));
    this.enderecosAlternativos =
        Map.copyOf(Objects.requireNonNull(enderecosAlternativos, "enderecosAlternativos"));
    rejeitarIdsDuplicados(this.equipamentos);
  }

  public UUID id() {
    return id;
  }

  public String nome() {
    return nome;
  }

  /** Endereço para onde enviar o paciente quando um equipamento deste tipo está indisponível. */
  public Optional<EnderecoAlternativo> enderecoAlternativoPara(TipoEquipamento tipo) {
    Objects.requireNonNull(tipo, "tipo");
    return Optional.ofNullable(enderecosAlternativos.get(tipo));
  }

  public TipoEquipamento tipoDoEquipamento(UUID idEquipamento) {
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    return buscar(idEquipamento).tipo();
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
    equipamento.transicionarPara(StatusEquipamento.INDISPONIVEL);
    eventos.add(evento);
  }

  /** Sem evento: o lote relê o status antes de cada envio (ADR 0002). */
  public void marcarEquipamentoDisponivel(UUID idEquipamento) {
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    buscar(idEquipamento).transicionarPara(StatusEquipamento.DISPONIVEL);
  }

  private Equipamento buscar(UUID idEquipamento) {
    return equipamentos.stream()
        .filter(equipamento -> equipamento.id().equals(idEquipamento))
        .findFirst()
        .orElseThrow(() -> new EquipamentoNaoEncontradoException(idEquipamento, id));
  }

  private static String exigirNomeValido(String nome) {
    Objects.requireNonNull(nome, "nome");
    if (nome.isBlank()) {
      throw new IllegalArgumentException("nome da UPA não pode ser vazio");
    }
    return nome;
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
