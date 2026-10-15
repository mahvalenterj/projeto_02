package br.gov.saude.upa.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escritas em SQL nativo e condicional: quem decide o vencedor é o banco, e o número de linhas
 * afetadas diz se esta execução ganhou. Cada operação roda na sua própria transação.
 */
interface AvisoPacienteJpaRepository extends Repository<AvisoPacienteJpa, ChaveDoAviso> {

  @Transactional(readOnly = true)
  List<AvisoPacienteJpa> findByEventoId(UUID eventoId);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO aviso_paciente
              (evento_id, agendamento_id, status, motivo_cancelamento, reservado_em, atualizado_em)
          VALUES
              (:eventoId, :agendamentoId, :status, CAST(:motivo AS VARCHAR), :reservadoEm, :atualizadoEm)
          ON CONFLICT (evento_id, agendamento_id) DO NOTHING
          """)
  int inserirSeAusente(
      @Param("eventoId") UUID eventoId,
      @Param("agendamentoId") UUID agendamentoId,
      @Param("status") String status,
      @Param("motivo") String motivo,
      @Param("reservadoEm") Instant reservadoEm,
      @Param("atualizadoEm") Instant atualizadoEm);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE aviso_paciente
             SET reservado_em = :novoReservadoEm, atualizado_em = :novoReservadoEm
           WHERE evento_id = :eventoId
             AND agendamento_id = :agendamentoId
             AND status = 'PENDENTE'
             AND reservado_em = :reservadoEmEsperado
          """)
  int reassumirSeAindaPendente(
      @Param("eventoId") UUID eventoId,
      @Param("agendamentoId") UUID agendamentoId,
      @Param("reservadoEmEsperado") Instant reservadoEmEsperado,
      @Param("novoReservadoEm") Instant novoReservadoEm);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE aviso_paciente
             SET status = :status,
                 motivo_cancelamento = CAST(:motivo AS VARCHAR),
                 atualizado_em = :atualizadoEm
           WHERE evento_id = :eventoId
             AND agendamento_id = :agendamentoId
             AND status = 'PENDENTE'
             AND reservado_em <= :atualizadoEm
          """)
  int registrarDesfechoSeAindaPendente(
      @Param("eventoId") UUID eventoId,
      @Param("agendamentoId") UUID agendamentoId,
      @Param("status") String status,
      @Param("motivo") String motivo,
      @Param("atualizadoEm") Instant atualizadoEm);
}
