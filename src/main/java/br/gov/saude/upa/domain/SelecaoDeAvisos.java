package br.gov.saude.upa.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decide quem entra no lote de avisos e em que ordem: agendamentos do equipamento, no dia da falha
 * (America/Sao_Paulo), que ainda não começaram; exame mais cedo primeiro, depois o paciente mais
 * velho (anos completos no dia do lote) e por fim o agendamento mais antigo.
 */
public class SelecaoDeAvisos {

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private final Clock clock;

  public SelecaoDeAvisos(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public List<CandidatoAAviso> selecionar(
      List<CandidatoAAviso> candidatos, UUID idEquipamento, Instant momentoFalha) {
    Objects.requireNonNull(candidatos, "candidatos");
    Objects.requireNonNull(idEquipamento, "idEquipamento");
    Objects.requireNonNull(momentoFalha, "momentoFalha");

    LocalDate diaDoLote = momentoFalha.atZone(FUSO).toLocalDate();
    Instant agora = clock.instant();

    Comparator<CandidatoAAviso> ordem =
        Comparator.comparing((CandidatoAAviso c) -> c.agendamento().inicioExame())
            .thenComparing(c -> idadeEmAnos(c, diaDoLote), Comparator.reverseOrder())
            .thenComparing(c -> c.agendamento().criadoEm());

    return candidatos.stream()
        .filter(c -> c.agendamento().idEquipamento().equals(idEquipamento))
        .filter(c -> diaEmSaoPaulo(c.agendamento().inicioExame()).equals(diaDoLote))
        .filter(c -> c.agendamento().inicioExame().isAfter(agora))
        .sorted(ordem)
        .toList();
  }

  private static LocalDate diaEmSaoPaulo(Instant instante) {
    return instante.atZone(FUSO).toLocalDate();
  }

  private static int idadeEmAnos(CandidatoAAviso candidato, LocalDate diaDoLote) {
    return Period.between(candidato.dataNascimentoPaciente(), diaDoLote).getYears();
  }
}
