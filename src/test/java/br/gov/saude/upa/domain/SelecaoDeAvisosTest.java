package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class SelecaoDeAvisosTest {

  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ID_TOMOGRAFO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ID_RAIO_X = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  /** 12:00 em São Paulo, dia 2026-10-11. */
  private static final Instant MOMENTO_FALHA = Instant.parse("2026-10-11T15:00:00Z");

  /** 12:05 em São Paulo: o lote roda logo depois da falha. */
  private static final Instant AGORA = Instant.parse("2026-10-11T15:05:00Z");

  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final LocalDate NASCIMENTO = LocalDate.of(1990, 6, 15);

  private static Clock relogioEm(Instant instante) {
    return Clock.fixed(instante, ZoneOffset.UTC);
  }

  private static SelecaoDeAvisos selecao() {
    return new SelecaoDeAvisos(relogioEm(AGORA));
  }

  private static UUID id(int numero) {
    return new UUID(0L, numero);
  }

  private static CandidatoAAviso candidato(
      int numero, UUID equipamento, Instant inicio, Instant criadoEm, LocalDate nascimento) {
    AgendamentoExame agendamento =
        new AgendamentoTomografia(
            id(numero), id(1000 + numero), ID_UPA, equipamento, criadoEm, inicio);
    return new CandidatoAAviso(agendamento, nascimento);
  }

  private static CandidatoAAviso candidato(int numero, Instant inicio) {
    return candidato(numero, ID_TOMOGRAFO, inicio, CRIADO_EM, NASCIMENTO);
  }

  private static List<UUID> idsDe(List<CandidatoAAviso> candidatos) {
    return candidatos.stream().map(c -> c.agendamento().id()).toList();
  }

  @Test
  void deveSelecionarSoAgendamentosDeHoje() {
    List<CandidatoAAviso> candidatos =
        List.of(
            candidato(1, Instant.parse("2026-10-11T18:00:00Z")),
            candidato(2, Instant.parse("2026-10-11T19:00:00Z")),
            candidato(3, Instant.parse("2026-10-11T21:00:00Z")),
            candidato(4, Instant.parse("2026-10-12T15:00:00Z")));

    List<CandidatoAAviso> selecionados =
        selecao().selecionar(candidatos, ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(idsDe(selecionados)).containsExactly(id(1), id(2), id(3));
  }

  @Test
  void deveUsarODiaDeSaoPauloEmVezDoDiaUtc() {
    // 23:59:59 em São Paulo ainda é dia 11, embora em UTC já seja dia 12.
    var ultimoSegundoDoDia = candidato(1, Instant.parse("2026-10-12T02:59:59Z"));
    // 00:00:00 em São Paulo já é dia 12.
    var primeiroSegundoDoDiaSeguinte = candidato(2, Instant.parse("2026-10-12T03:00:00Z"));

    List<CandidatoAAviso> selecionados =
        selecao()
            .selecionar(
                List.of(ultimoSegundoDoDia, primeiroSegundoDoDiaSeguinte),
                ID_TOMOGRAFO,
                MOMENTO_FALHA);

    assertThat(idsDe(selecionados)).containsExactly(id(1));
  }

  @Test
  void deveUsarODiaDoMomentoFalhaMesmoQuandoOLoteRodarDepoisDaMeiaNoite() {
    // Falha às 23:50 do dia 11 (São Paulo); o evento é reentregue às 00:30 do dia 12.
    Instant falha = Instant.parse("2026-10-12T02:50:00Z");
    var selecaoAposMeiaNoite =
        new SelecaoDeAvisos(relogioEm(Instant.parse("2026-10-12T03:30:00Z")));
    // 01:00 do dia 12 (futuro, mas fora do dia da falha).
    var exameDoDiaSeguinte = candidato(1, Instant.parse("2026-10-12T04:00:00Z"));

    List<CandidatoAAviso> selecionados =
        selecaoAposMeiaNoite.selecionar(List.of(exameDoDiaSeguinte), ID_TOMOGRAFO, falha);

    assertThat(selecionados).isEmpty();
  }

  @Test
  void deveSelecionarSoAgendamentosDoEquipamentoAfetado() {
    Instant inicio = Instant.parse("2026-10-11T18:00:00Z");
    var daTomografia = candidato(1, ID_TOMOGRAFO, inicio, CRIADO_EM, NASCIMENTO);
    var doRaioX =
        new CandidatoAAviso(
            new AgendamentoRaioX(id(2), id(1002), ID_UPA, ID_RAIO_X, CRIADO_EM, inicio),
            NASCIMENTO);

    List<CandidatoAAviso> selecionados =
        selecao().selecionar(List.of(daTomografia, doRaioX), ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(idsDe(selecionados)).containsExactly(id(1));
  }

  @Test
  void deveIgnorarAgendamentoComExameJaIniciado() {
    var jaIniciado = candidato(1, AGORA.minusSeconds(60));

    List<CandidatoAAviso> selecionados =
        selecao().selecionar(List.of(jaIniciado), ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(selecionados).isEmpty();
  }

  @Test
  void deveIgnorarExameQueIniciaExatamenteAgora() {
    var iniciaAgora = candidato(1, AGORA);

    List<CandidatoAAviso> selecionados =
        selecao().selecionar(List.of(iniciaAgora), ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(selecionados).isEmpty();
  }

  @Test
  void deveManterExameQueIniciaDepoisDeAgora() {
    var iniciaDepois = candidato(1, AGORA.plusSeconds(1));

    List<CandidatoAAviso> selecionados =
        selecao().selecionar(List.of(iniciaDepois), ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(idsDe(selecionados)).containsExactly(id(1));
  }

  private record CenarioDeOrdem(
      String nome, List<CandidatoAAviso> entrada, List<UUID> ordemEsperada) {}

  @TestFactory
  Stream<DynamicTest> deveOrdenarPorHorarioDepoisIdadeDepoisCriacao() {
    Instant dezHoras = Instant.parse("2026-10-11T18:00:00Z");
    Instant onzeHoras = Instant.parse("2026-10-11T19:00:00Z");
    Instant cedo = Instant.parse("2026-10-01T09:00:00Z");
    Instant tarde = Instant.parse("2026-10-05T09:00:00Z");

    List<CenarioDeOrdem> cenarios =
        List.of(
            new CenarioDeOrdem(
                "horario do exame vem antes de idade e criacao",
                List.of(
                    candidato(1, ID_TOMOGRAFO, onzeHoras, cedo, LocalDate.of(1930, 1, 1)),
                    candidato(2, ID_TOMOGRAFO, dezHoras, tarde, LocalDate.of(2000, 1, 1))),
                List.of(id(2), id(1))),
            new CenarioDeOrdem(
                "mesmo horario: mais idoso primeiro, mesmo com criacao mais recente",
                List.of(
                    candidato(1, ID_TOMOGRAFO, dezHoras, cedo, LocalDate.of(1996, 1, 1)),
                    candidato(2, ID_TOMOGRAFO, dezHoras, tarde, LocalDate.of(1956, 1, 1))),
                List.of(id(2), id(1))),
            new CenarioDeOrdem(
                "mesma idade em anos completos: vale a criacao mais antiga",
                List.of(
                    candidato(1, ID_TOMOGRAFO, dezHoras, tarde, LocalDate.of(1956, 1, 10)),
                    candidato(2, ID_TOMOGRAFO, dezHoras, cedo, LocalDate.of(1956, 3, 5))),
                List.of(id(2), id(1))),
            new CenarioDeOrdem(
                "aniversario no dia do lote conta o ano completo",
                List.of(
                    candidato(1, ID_TOMOGRAFO, dezHoras, cedo, LocalDate.of(1956, 10, 12)),
                    candidato(2, ID_TOMOGRAFO, dezHoras, tarde, LocalDate.of(1956, 10, 11))),
                List.of(id(2), id(1))),
            new CenarioDeOrdem(
                "empate total preserva a ordem de entrada",
                List.of(
                    candidato(3, ID_TOMOGRAFO, dezHoras, cedo, NASCIMENTO),
                    candidato(1, ID_TOMOGRAFO, dezHoras, cedo, NASCIMENTO),
                    candidato(2, ID_TOMOGRAFO, dezHoras, cedo, NASCIMENTO)),
                List.of(id(3), id(1), id(2))));

    return cenarios.stream()
        .map(
            cenario ->
                DynamicTest.dynamicTest(
                    cenario.nome(),
                    () ->
                        assertThat(
                                idsDe(
                                    selecao()
                                        .selecionar(
                                            cenario.entrada(), ID_TOMOGRAFO, MOMENTO_FALHA)))
                            .containsExactlyElementsOf(cenario.ordemEsperada())));
  }

  @Test
  void deveDevolverListaVaziaQuandoNaoHouverCandidatos() {
    assertThat(selecao().selecionar(List.of(), ID_TOMOGRAFO, MOMENTO_FALHA)).isEmpty();
  }

  @Test
  void deveDevolverListaImutavel() {
    List<CandidatoAAviso> selecionados =
        selecao()
            .selecionar(
                List.of(candidato(1, Instant.parse("2026-10-11T18:00:00Z"))),
                ID_TOMOGRAFO,
                MOMENTO_FALHA);

    assertThatThrownBy(selecionados::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void naoDeveAlterarAListaRecebida() {
    List<CandidatoAAviso> entrada = new ArrayList<>();
    entrada.add(candidato(1, Instant.parse("2026-10-11T19:00:00Z")));
    entrada.add(candidato(2, Instant.parse("2026-10-11T18:00:00Z")));
    entrada.add(candidato(3, Instant.parse("2026-10-12T18:00:00Z")));
    List<CandidatoAAviso> copiaAntes = List.copyOf(entrada);

    selecao().selecionar(entrada, ID_TOMOGRAFO, MOMENTO_FALHA);

    assertThat(entrada).containsExactlyElementsOf(copiaAntes);
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    List<CandidatoAAviso> candidatos = List.of();

    assertThatThrownBy(() -> new SelecaoDeAvisos(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> selecao().selecionar(null, ID_TOMOGRAFO, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> selecao().selecionar(candidatos, null, MOMENTO_FALHA))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> selecao().selecionar(candidatos, ID_TOMOGRAFO, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveExporODiaDoLoteEmSaoPaulo() {
    // 02:30 UTC do dia 12 ainda é 23:30 do dia 11 em São Paulo.
    Instant madrugadaUtc = Instant.parse("2026-10-12T02:30:00Z");

    assertThat(selecao().diaDoLote(madrugadaUtc)).isEqualTo(LocalDate.of(2026, 10, 11));
    assertThat(selecao().diaDoLote(Instant.parse("2026-10-12T03:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 10, 12));
  }

  @Test
  void deveRejeitarMomentoNuloAoExporODiaDoLote() {
    SelecaoDeAvisos selecao = selecao();

    assertThatThrownBy(() -> selecao.diaDoLote(null)).isInstanceOf(NullPointerException.class);
  }
}
