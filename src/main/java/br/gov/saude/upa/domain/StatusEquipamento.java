package br.gov.saude.upa.domain;

public enum StatusEquipamento {
  DISPONIVEL,
  INDISPONIVEL,
  EM_MANUTENCAO;

  /**
   * Tabela de transições da spec 001 (§4). Sem {@code default}: novo status quebra a compilação.
   */
  public boolean podeTransicionarPara(StatusEquipamento destino) {
    return switch (this) {
      case DISPONIVEL -> destino == INDISPONIVEL;
      case INDISPONIVEL -> destino == DISPONIVEL;
      case EM_MANUTENCAO -> false;
    };
  }
}
