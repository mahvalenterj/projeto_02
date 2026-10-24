package br.gov.saude.upa.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface AgendamentoExameJpaRepository extends Repository<AgendamentoExameJpa, UUID> {

  /**
   * Uma só consulta (agendamento + nascimento do paciente). Janela semiaberta {@code [inicio,
   * fim)}. Sem chave estrangeira: a junção é por id, externa para não perder o agendamento cujo
   * paciente não existe (o adapter decide o que fazer com ele).
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select new br.gov.saude.upa.infrastructure.persistence.LinhaDeCandidato(
          a.id, a.tipo, a.pacienteId, a.upaId, a.equipamentoId, a.criadoEm, a.inicioExame,
          p.dataNascimento)
        from AgendamentoExameJpa a
        left join PacienteJpa p on p.id = a.pacienteId
       where a.equipamentoId = :idEquipamento
         and a.inicioExame >= :inicio
         and a.inicioExame < :fim
       order by a.inicioExame, a.id
      """)
  List<LinhaDeCandidato> buscarDoEquipamentoNaJanela(
      @Param("idEquipamento") UUID idEquipamento,
      @Param("inicio") Instant inicio,
      @Param("fim") Instant fim);
}
