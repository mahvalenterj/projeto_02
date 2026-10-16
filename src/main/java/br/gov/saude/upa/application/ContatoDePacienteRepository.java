package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.ContatoDoPaciente;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Porta de saída dos contatos dos pacientes, consultados em lote. */
public interface ContatoDePacienteRepository {

  /** Contatos indexados pelo id do paciente; paciente sem registro fica fora do mapa. */
  Map<UUID, ContatoDoPaciente> buscarPorIds(Set<UUID> idsDePaciente);
}
