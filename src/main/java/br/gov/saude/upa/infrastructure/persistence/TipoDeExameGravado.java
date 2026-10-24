package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.AgendamentoExame;
import br.gov.saude.upa.domain.AgendamentoRaioX;
import br.gov.saude.upa.domain.AgendamentoTomografia;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Mapa tipo gravado em {@code agendamento_exame.tipo} para o construtor do agendamento de domínio.
 * Novo tipo de exame = nova entrada aqui (e no CHECK da tabela), sem {@code if/else} por tipo.
 */
enum TipoDeExameGravado {
  TOMOGRAFIA(AgendamentoTomografia::new),
  RAIO_X(AgendamentoRaioX::new);

  @FunctionalInterface
  private interface Construtor {
    AgendamentoExame criar(
        UUID id, UUID idPaciente, UUID idUpa, UUID idEquipamento, Instant criadoEm, Instant inicio);
  }

  private final Construtor construtor;

  TipoDeExameGravado(Construtor construtor) {
    this.construtor = construtor;
  }

  /** Vazio quando o tipo gravado não tem construtor mapeado. */
  static Optional<TipoDeExameGravado> de(String tipo) {
    return Arrays.stream(values()).filter(candidato -> candidato.name().equals(tipo)).findFirst();
  }

  AgendamentoExame montar(LinhaDeCandidato linha) {
    return construtor.criar(
        linha.id(),
        linha.idPaciente(),
        linha.idUpa(),
        linha.idEquipamento(),
        linha.criadoEm(),
        linha.inicioExame());
  }
}
