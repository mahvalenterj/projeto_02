package br.gov.saude.upa.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface PacienteJpaRepository extends Repository<PacienteJpa, UUID> {

  @Transactional(readOnly = true)
  @Query(
      """
      select new br.gov.saude.upa.infrastructure.persistence.LinhaDeContato(
          p.id, p.telefone, p.email)
        from PacienteJpa p
       where p.id in :ids
      """)
  List<LinhaDeContato> buscarContatos(@Param("ids") Collection<UUID> ids);
}
