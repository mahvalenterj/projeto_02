package br.gov.saude.upa.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UpaJpaRepository extends JpaRepository<UpaJpa, UUID> {}
