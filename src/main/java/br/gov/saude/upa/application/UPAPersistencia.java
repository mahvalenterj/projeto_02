package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.UPA;

/**
 * Porta de leitura e escrita da UPA, separada da leitura do lote (ISP): o lote só lê, os casos de
 * uso de status leem e gravam. {@code salvar} detecta alteração concorrente pelo {@code @Version}
 * do equipamento.
 */
public interface UPAPersistencia extends UPARepository {

  /**
   * Grava a UPA recebida. Uma UPA lida por {@link #buscar} grava só os equipamentos cujo status
   * mudou.
   *
   * @throws ConflitoDeConcorrenciaException se outra operação gravou o equipamento antes
   */
  void salvar(UPA upa);
}
