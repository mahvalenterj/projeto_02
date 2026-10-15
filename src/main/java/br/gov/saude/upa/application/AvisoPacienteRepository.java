package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.AvisoPaciente;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Porta de saída dos avisos por evento e agendamento (ADR 0001). A invariante sob concorrência fica
 * no banco: cada operação devolve se esta execução foi a vencedora.
 *
 * <p>O banco guarda instantes em microssegundos; os instantes lidos já vêm nessa precisão.
 */
public interface AvisoPacienteRepository {

  /** Avisos já existentes do evento, numa só consulta, indexados pelo id do agendamento. */
  Map<UUID, AvisoPaciente> buscarPorEvento(UUID eventoId);

  /**
   * Grava a reserva {@code PENDENTE}. {@code false} se a chave já existe (outra execução ganhou).
   */
  boolean reservar(AvisoPaciente aviso);

  /**
   * Renova a reserva abandonada. Só vale se a linha ainda estiver {@code PENDENTE} com exatamente
   * esse {@code reservado_em}; {@code false} se outra execução já a assumiu ou ela teve desfecho.
   */
  boolean reassumir(
      UUID eventoId, UUID agendamentoId, Instant reservadoEmEsperado, Instant novoReservadoEm);

  /**
   * Grava o desfecho (status, motivo e {@code atualizado_em}). Só vale se a linha ainda estiver
   * {@code PENDENTE} e a reserva não for posterior ao instante do desfecho. {@code false} se já
   * tinha desfecho (nunca sobrescreve) ou se outra execução reassumiu a reserva depois desse
   * instante (a execução que perdeu a reserva recebe {@code false}, não exceção).
   */
  boolean registrarDesfecho(AvisoPaciente aviso);
}
