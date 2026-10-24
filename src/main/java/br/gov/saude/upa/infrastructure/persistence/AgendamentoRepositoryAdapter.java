package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.AgendamentoDescartado;
import br.gov.saude.upa.application.AgendamentoRepository;
import br.gov.saude.upa.application.AgendamentosDoDia;
import br.gov.saude.upa.domain.CandidatoAAviso;
import br.gov.saude.upa.domain.Fuso;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code @Component} e não {@code @Repository}: com {@code @Repository} o Spring converteria as
 * pré-condições deste adapter em exceções do Spring, que não podem vazar pela porta.
 */
@Component
class AgendamentoRepositoryAdapter implements AgendamentoRepository {

  private static final Logger LOG = LoggerFactory.getLogger(AgendamentoRepositoryAdapter.class);

  private final AgendamentoExameJpaRepository jpa;

  AgendamentoRepositoryAdapter(AgendamentoExameJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public AgendamentosDoDia buscarDoEquipamentoNoDia(UUID idEquipamento, LocalDate dia) {
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    Objects.requireNonNull(dia, "dia");
    Instant inicio = dia.atStartOfDay(Fuso.SAO_PAULO).toInstant();
    Instant fim = dia.plusDays(1).atStartOfDay(Fuso.SAO_PAULO).toInstant();

    List<CandidatoAAviso> candidatos = new ArrayList<>();
    List<AgendamentoDescartado> descartados = new ArrayList<>();
    for (LinhaDeCandidato linha : jpa.buscarDoEquipamentoNaJanela(idEquipamento, inicio, fim)) {
      Optional<CandidatoAAviso> candidato = paraCandidato(linha);
      if (candidato.isPresent()) {
        candidatos.add(candidato.get());
      } else {
        // Dado inconsistente (sem paciente ou tipo desconhecido): o caso de uso o encaminha à
        // revisão manual. Só o id do agendamento vai para o log.
        LOG.warn("Agendamento {} com dado inconsistente; descartado do lote", linha.id());
        descartados.add(new AgendamentoDescartado(linha.id(), linha.inicioExame()));
      }
    }
    return new AgendamentosDoDia(candidatos, descartados);
  }

  private static Optional<CandidatoAAviso> paraCandidato(LinhaDeCandidato linha) {
    if (linha.dataNascimento() == null) {
      return Optional.empty();
    }
    return TipoDeExameGravado.de(linha.tipo())
        .map(tipo -> new CandidatoAAviso(tipo.montar(linha), linha.dataNascimento()));
  }
}
