package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UPA reconstituída do banco, carregando a versão e o status lidos de cada equipamento. O domínio
 * não conhece versão (é detalhe de persistência): quem a guarda é este adapter, e {@code salvar} a
 * usa para o lock otimista e para gravar só o que mudou.
 */
final class UpaLida extends UPA {

  private final Map<UUID, Long> versoes = new HashMap<>();
  private final Map<UUID, StatusEquipamento> statusLidos = new HashMap<>();

  UpaLida(
      UUID id,
      String nome,
      List<Equipamento> equipamentos,
      Map<TipoEquipamento, EnderecoAlternativo> enderecos,
      Map<UUID, Long> versoesLidas) {
    super(id, nome, equipamentos, enderecos);
    versoes.putAll(versoesLidas);
    for (Equipamento equipamento : equipamentos) {
      statusLidos.put(equipamento.id(), equipamento.status());
    }
  }

  Long versao(UUID idEquipamento) {
    return versoes.get(idEquipamento);
  }

  boolean mudou(Equipamento equipamento) {
    return statusLidos.get(equipamento.id()) != equipamento.status();
  }

  void registrarGravacao(UUID idEquipamento, Long novaVersao, StatusEquipamento status) {
    versoes.put(idEquipamento, novaVersao);
    statusLidos.put(idEquipamento, status);
  }
}
