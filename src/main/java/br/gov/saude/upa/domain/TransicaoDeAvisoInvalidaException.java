package br.gov.saude.upa.domain;

import java.util.UUID;

public class TransicaoDeAvisoInvalidaException extends RuntimeException {

  public TransicaoDeAvisoInvalidaException(UUID eventoId, UUID agendamentoId, String detalhe) {
    super("Aviso do evento " + eventoId + " para o agendamento " + agendamentoId + ": " + detalhe);
  }
}
