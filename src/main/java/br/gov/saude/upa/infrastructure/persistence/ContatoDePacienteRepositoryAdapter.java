package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.ContatoDePacienteRepository;
import br.gov.saude.upa.domain.ContatoDoPaciente;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@code @Component} pelo mesmo motivo de {@code AvisoPacienteRepositoryAdapter}. */
@Component
class ContatoDePacienteRepositoryAdapter implements ContatoDePacienteRepository {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContatoDePacienteRepositoryAdapter.class);

  /** Tamanho máximo da lista do {@code IN} por consulta. */
  static final int TAMANHO_DO_BLOCO = 1_000;

  private final PacienteJpaRepository jpa;

  ContatoDePacienteRepositoryAdapter(PacienteJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Map<UUID, ContatoDoPaciente> buscarPorIds(Set<UUID> idsDePaciente) {
    Objects.requireNonNull(idsDePaciente, "idsDePaciente");
    List<UUID> ids = new ArrayList<>(idsDePaciente);
    Map<UUID, ContatoDoPaciente> contatos = new HashMap<>();
    for (int de = 0; de < ids.size(); de += TAMANHO_DO_BLOCO) {
      List<UUID> bloco = ids.subList(de, Math.min(de + TAMANHO_DO_BLOCO, ids.size()));
      for (LinhaDeContato linha : jpa.buscarContatos(bloco)) {
        contatos.put(linha.id(), paraContato(linha));
      }
    }
    return Map.copyOf(contatos);
  }

  /**
   * Defesa em profundidade: o CHECK do banco já impede contato em branco. Se mesmo assim houver
   * (dado antigo, carga externa), o registro é suspeito e o contato INTEIRO vira ausente (os dois
   * campos nulos), sem derrubar o lote; o caso de uso trata a falta de contato como SEM_CONTATO. Só
   * o id do paciente vai para o log.
   */
  private static ContatoDoPaciente paraContato(LinhaDeContato linha) {
    if (emBranco(linha.telefone()) || emBranco(linha.email())) {
      LOG.warn(
          "Paciente {} com contato em branco; contato inteiro tratado como ausente", linha.id());
      return new ContatoDoPaciente(null, null);
    }
    return new ContatoDoPaciente(linha.telefone(), linha.email());
  }

  private static boolean emBranco(String valor) {
    return valor != null && valor.isBlank();
  }
}
