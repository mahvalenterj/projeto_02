package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.UPA;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída para carregar a UPA (Aggregate Root) pelo id. */
public interface UPARepository {

  Optional<UPA> buscar(UUID idUpa);
}
