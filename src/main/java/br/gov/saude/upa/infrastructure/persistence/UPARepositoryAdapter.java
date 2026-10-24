package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.ConflitoDeConcorrenciaException;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code @Component} e não {@code @Repository}: o Spring converteria as pré-condições deste adapter
 * em exceções suas. O conflito de versão é traduzido aqui para a exceção da camada application,
 * para nada do Spring vazar pela porta.
 */
@Component
class UPARepositoryAdapter implements UPAPersistencia {

  private final UpaJpaRepository upas;
  private final EquipamentoJpaRepository equipamentos;

  UPARepositoryAdapter(UpaJpaRepository upas, EquipamentoJpaRepository equipamentos) {
    this.upas = upas;
    this.equipamentos = equipamentos;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UPA> buscar(UUID idUpa) {
    Objects.requireNonNull(idUpa, "idUpa");
    return upas.findById(idUpa)
        .map(upa -> paraDominio(upa, equipamentos.findByUpaIdOrderById(idUpa)));
  }

  /** Uma UPA que não veio de {@link #buscar} é nova e é inserida por inteiro. */
  @Override
  @Transactional
  public void salvar(UPA upa) {
    Objects.requireNonNull(upa, "upa");
    try {
      if (upa instanceof UpaLida lida) {
        atualizar(lida);
      } else {
        inserir(upa);
      }
    } catch (OptimisticLockingFailureException e) {
      throw new ConflitoDeConcorrenciaException(upa.id());
    }
  }

  private void atualizar(UpaLida upa) {
    for (Equipamento equipamento : upa.equipamentos()) {
      if (!upa.mudou(equipamento)) {
        continue;
      }
      EquipamentoJpa gravado =
          equipamentos.saveAndFlush(
              new EquipamentoJpa(
                  equipamento.id(),
                  upa.id(),
                  equipamento.tipo(),
                  equipamento.status(),
                  upa.versao(equipamento.id())));
      upa.registrarGravacao(equipamento.id(), gravado.version(), gravado.status());
    }
  }

  private void inserir(UPA upa) {
    Map<TipoEquipamento, String> enderecos = new EnumMap<>(TipoEquipamento.class);
    for (TipoEquipamento tipo : TipoEquipamento.values()) {
      upa.enderecoAlternativoPara(tipo)
          .ifPresent(endereco -> enderecos.put(tipo, endereco.texto()));
    }
    upas.saveAndFlush(new UpaJpa(upa.id(), upa.nome(), enderecos));
    for (Equipamento equipamento : upa.equipamentos()) {
      equipamentos.saveAndFlush(
          new EquipamentoJpa(
              equipamento.id(), upa.id(), equipamento.tipo(), equipamento.status(), null));
    }
  }

  private static UPA paraDominio(UpaJpa upa, List<EquipamentoJpa> linhas) {
    Map<UUID, Long> versoes = new HashMap<>();
    List<Equipamento> equipamentosDoDominio =
        linhas.stream()
            .map(
                linha -> {
                  versoes.put(linha.id(), linha.version());
                  return new Equipamento(linha.id(), linha.tipo(), linha.status());
                })
            .toList();
    Map<TipoEquipamento, EnderecoAlternativo> enderecos = new EnumMap<>(TipoEquipamento.class);
    upa.enderecos().forEach((tipo, texto) -> enderecos.put(tipo, new EnderecoAlternativo(texto)));
    return new UpaLida(upa.id(), upa.nome(), equipamentosDoDominio, enderecos, versoes);
  }
}
