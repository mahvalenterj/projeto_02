package br.gov.saude.upa.application;

import java.util.UUID;

/** A thread do lote foi interrompida; o que já tinha sido despachado terminou. */
public class LoteInterrompidoException extends RuntimeException {

  public LoteInterrompidoException(UUID eventoId) {
    super("Lote do evento " + eventoId + " interrompido");
  }
}
