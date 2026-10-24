package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.StatusEquipamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EquipamentoJpaRepository extends JpaRepository<EquipamentoJpa, UUID> {

  List<EquipamentoJpa> findByUpaIdOrderById(UUID upaId);

  /** Consulta escalar: sempre vai ao banco, não passa pelo cache da sessão. */
  @Query("select e.status from EquipamentoJpa e where e.id = :id")
  Optional<StatusEquipamento> buscarStatus(@Param("id") UUID id);
}
