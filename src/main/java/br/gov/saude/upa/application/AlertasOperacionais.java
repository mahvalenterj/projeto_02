package br.gov.saude.upa.application;

import java.util.UUID;

/**
 * Porta de saída dos alertas para a operação. Só ids e o nome da classe do erro: nada do paciente.
 */
public interface AlertasOperacionais {

  /** O canal não confirmou nem negou o envio: a reserva fica {@code PENDENTE}. */
  void avisoIndeterminado(UUID eventoId, UUID agendamentoId);

  /**
   * Falha inesperada ao processar um agendamento. {@code tipoDoErro} é o nome da classe da exceção,
   * nunca a mensagem dela.
   */
  void falhaInesperada(UUID eventoId, UUID agendamentoId, String tipoDoErro);

  /**
   * Agendamento sem paciente cadastrado ou de tipo de exame desconhecido, encaminhado à revisão
   * manual.
   */
  void dadoInconsistente(UUID eventoId, UUID agendamentoId);
}
