package br.gov.saude.upa.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface RevisaoManualJpaRepository extends Repository<RevisaoManualJpa, ChaveDaRevisaoManual> {

  @Transactional(readOnly = true)
  @Query("select r.agendamentoId from RevisaoManualJpa r where r.eventoId = :eventoId")
  List<UUID> agendamentosDoEvento(@Param("eventoId") UUID eventoId);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO revisao_manual (evento_id, agendamento_id, motivo, registrado_em)
          VALUES (:eventoId, :agendamentoId, :motivo, :registradoEm)
          ON CONFLICT (evento_id, agendamento_id) DO NOTHING
          """)
  int inserirSeAusente(
      @Param("eventoId") UUID eventoId,
      @Param("agendamentoId") UUID agendamentoId,
      @Param("motivo") String motivo,
      @Param("registradoEm") Instant registradoEm);
}
