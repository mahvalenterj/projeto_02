package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.AvisoPacienteRepository;
import br.gov.saude.upa.domain.AvisoPaciente;
import br.gov.saude.upa.domain.MotivoCancelamento;
import br.gov.saude.upa.domain.StatusAviso;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code @Component} e não {@code @Repository}: a tradução de erros de banco já é feita pelos
 * repositórios Spring Data; com {@code @Repository} o Spring também converteria as pré-condições
 * deste adapter em exceções do Spring, que não podem vazar pela porta.
 */
@Component
class AvisoPacienteRepositoryAdapter implements AvisoPacienteRepository {

  private final AvisoPacienteJpaRepository jpa;

  AvisoPacienteRepositoryAdapter(AvisoPacienteJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Map<UUID, AvisoPaciente> buscarPorEvento(UUID eventoId) {
    Objects.requireNonNull(eventoId, "eventoId");
    return jpa.findByEventoId(eventoId).stream()
        .map(AvisoPacienteRepositoryAdapter::paraDominio)
        .collect(Collectors.toUnmodifiableMap(AvisoPaciente::agendamentoId, aviso -> aviso));
  }

  @Override
  public boolean reservar(AvisoPaciente aviso) {
    Objects.requireNonNull(aviso, "aviso");
    if (aviso.status() != StatusAviso.PENDENTE) {
      throw new IllegalArgumentException(
          "Só a reserva PENDENTE pode ser gravada (aviso " + aviso + ")");
    }
    return jpa.inserirSeAusente(
            aviso.eventoId(),
            aviso.agendamentoId(),
            aviso.status().name(),
            null,
            micros(aviso.reservadoEm()),
            micros(aviso.atualizadoEm()))
        == 1;
  }

  @Override
  public boolean reassumir(
      UUID eventoId, UUID agendamentoId, Instant reservadoEmEsperado, Instant novoReservadoEm) {
    Objects.requireNonNull(eventoId, "eventoId");
    Objects.requireNonNull(agendamentoId, "agendamentoId");
    Objects.requireNonNull(reservadoEmEsperado, "reservadoEmEsperado");
    Objects.requireNonNull(novoReservadoEm, "novoReservadoEm");
    return jpa.reassumirSeAindaPendente(
            eventoId, agendamentoId, micros(reservadoEmEsperado), micros(novoReservadoEm))
        == 1;
  }

  @Override
  public boolean registrarDesfecho(AvisoPaciente aviso) {
    Objects.requireNonNull(aviso, "aviso");
    if (aviso.status() == StatusAviso.PENDENTE) {
      throw new IllegalArgumentException(
          "Desfecho exige CONFIRMADO ou CANCELADO, mas o aviso está PENDENTE (" + aviso + ")");
    }
    return jpa.registrarDesfechoSeAindaPendente(
            aviso.eventoId(),
            aviso.agendamentoId(),
            aviso.status().name(),
            aviso.motivoCancelamento().map(MotivoCancelamento::name).orElse(null),
            micros(aviso.atualizadoEm()))
        == 1;
  }

  private static AvisoPaciente paraDominio(AvisoPacienteJpa linha) {
    return AvisoPaciente.reconstituir(
        linha.eventoId(),
        linha.agendamentoId(),
        linha.status(),
        linha.reservadoEm(),
        linha.atualizadoEm(),
        linha.motivoCancelamento());
  }

  /**
   * O Postgres guarda microssegundos; truncar na escrita evita comparação que falha em silêncio.
   */
  private static Instant micros(Instant instante) {
    return instante.truncatedTo(ChronoUnit.MICROS);
  }
}
