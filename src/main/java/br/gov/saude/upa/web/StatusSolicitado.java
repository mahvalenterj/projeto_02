package br.gov.saude.upa.web;

/**
 * Os dois status que o operador pode pedir pela API (spec 001 §8). {@code EM_MANUTENCAO} existe no
 * domínio, mas não é pedido por aqui: é transição inválida, como qualquer outro valor.
 */
enum StatusSolicitado {
  INDISPONIVEL,
  DISPONIVEL;

  /**
   * @throws CorpoInvalidoException sem {@code status} no corpo (400)
   * @throws StatusSolicitadoInvalidoException valor fora dos dois aceitos, sem tolerar minúsculas
   *     (422)
   */
  static StatusSolicitado de(String valor) {
    if (valor == null) {
      throw new CorpoInvalidoException();
    }
    for (StatusSolicitado status : values()) {
      if (status.name().equals(valor)) {
        return status;
      }
    }
    throw new StatusSolicitadoInvalidoException();
  }
}
