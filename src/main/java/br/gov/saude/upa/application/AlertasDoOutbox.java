package br.gov.saude.upa.application;

import java.util.UUID;

/** Alerta do despachante. Só ids, contagem e o nome da classe do erro. */
public interface AlertasDoOutbox {

  void eventoEsgotado(UUID eventoId, int tentativas, String tipoDoUltimoErro);
}
