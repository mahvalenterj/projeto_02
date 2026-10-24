package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.AgendamentoDescartado;
import br.gov.saude.upa.application.AgendamentoRepository;
import br.gov.saude.upa.application.AgendamentosDoDia;
import br.gov.saude.upa.domain.AgendamentoRaioX;
import br.gov.saude.upa.domain.AgendamentoTomografia;
import br.gov.saude.upa.domain.CandidatoAAviso;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dados sintéticos. O banco é compartilhado: cada teste usa ids novos e filtra pelo seu
 * equipamento.
 */
class AgendamentoRepositoryTest extends PostgresIntegrationTest {

  // São Paulo é UTC-3 o ano todo (sem horário de verão desde 2019): o dia 2026-10-12 vai de
  // 2026-10-12T03:00Z (inclusive) a 2026-10-13T03:00Z (exclusive).
  private static final LocalDate DIA = LocalDate.of(2026, 10, 12);
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");

  @Autowired private AgendamentoRepository repositorio;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory fabrica;
  @Autowired private TransactionTemplate transacao;

  private boolean estatisticasEstavamLigadas;

  private Statistics ligarEstatisticas() {
    Statistics estatisticas = fabrica.unwrap(SessionFactory.class).getStatistics();
    estatisticasEstavamLigadas = estatisticas.isStatisticsEnabled();
    estatisticas.setStatisticsEnabled(true);
    estatisticas.clear();
    return estatisticas;
  }

  @AfterEach
  void restaurarEstatisticas() {
    fabrica
        .unwrap(SessionFactory.class)
        .getStatistics()
        .setStatisticsEnabled(estatisticasEstavamLigadas);
  }

  private List<CandidatoAAviso> candidatos(UUID idEquipamento, LocalDate dia) {
    return repositorio.buscarDoEquipamentoNoDia(idEquipamento, dia).candidatos();
  }

  private UUID paciente(LocalDate nascimento) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?, ?)",
        id,
        "Paciente Sintetico",
        null,
        null,
        java.sql.Date.valueOf(nascimento));
    return id;
  }

  private UUID agendamento(
      String tipo, UUID idPaciente, UUID idUpa, UUID idEquipamento, Instant inicio) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id, criado_em,"
            + " inicio_exame) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        tipo,
        idPaciente,
        idUpa,
        idEquipamento,
        Timestamp.from(CRIADO_EM),
        Timestamp.from(inicio));
    return id;
  }

  private UUID tomografia(UUID idPaciente, UUID idEquipamento, String inicio) {
    return agendamento(
        "TOMOGRAFIA", idPaciente, UUID.randomUUID(), idEquipamento, Instant.parse(inicio));
  }

  private static List<UUID> ids(List<CandidatoAAviso> candidatos) {
    return candidatos.stream().map(c -> c.agendamento().id()).toList();
  }

  @Test
  void deveIncluirOPrimeiroInstanteDoDiaEmSaoPauloEExcluirAs2330DoDiaAnterior() {
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    UUID ontem2330 = tomografia(pac, equipamento, "2026-10-12T02:30:00Z");
    UUID meiaNoite = tomografia(pac, equipamento, "2026-10-12T03:00:00Z");
    UUID hoje0030 = tomografia(pac, equipamento, "2026-10-12T03:30:00Z");

    List<UUID> achados = ids(candidatos(equipamento, DIA));

    assertThat(achados).containsExactlyInAnyOrder(meiaNoite, hoje0030).doesNotContain(ontem2330);
  }

  @Test
  void deveIncluirAs2330DeSaoPauloEExcluirAMeiaNoiteDoDiaSeguinte() {
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    UUID hoje2330 = tomografia(pac, equipamento, "2026-10-13T02:30:00Z");
    UUID amanha0000 = tomografia(pac, equipamento, "2026-10-13T03:00:00Z");

    List<UUID> achados = ids(candidatos(equipamento, DIA));

    assertThat(achados).containsExactly(hoje2330).doesNotContain(amanha0000);
  }

  @Test
  void deveUsarODiaDeSaoPauloMesmoQuandoOUtcJaViraOutroDia() {
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    // 2026-10-13T01:00Z ainda é 22:00 do dia 12 em São Paulo (e já é dia 13 em UTC).
    UUID tardeNoDia12 = tomografia(pac, equipamento, "2026-10-13T01:00:00Z");

    assertThat(ids(candidatos(equipamento, DIA))).containsExactly(tardeNoDia12);
    assertThat(candidatos(equipamento, DIA.plusDays(1))).isEmpty();
  }

  @Test
  void deveTrazerSoOsAgendamentosDoEquipamentoPedido() {
    UUID equipamento = UUID.randomUUID();
    UUID outroEquipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    UUID doEquipamento = tomografia(pac, equipamento, "2026-10-12T15:00:00Z");
    tomografia(pac, outroEquipamento, "2026-10-12T15:00:00Z");

    assertThat(ids(candidatos(equipamento, DIA))).containsExactly(doEquipamento);
  }

  @Test
  void deveMontarCadaTipoDeExameComTodosOsCamposEANascimentoDoPaciente() {
    UUID equipamento = UUID.randomUUID();
    UUID upa = UUID.randomUUID();
    LocalDate nascimentoA = LocalDate.of(1951, 3, 20);
    LocalDate nascimentoB = LocalDate.of(2001, 12, 31);
    UUID pacA = paciente(nascimentoA);
    UUID pacB = paciente(nascimentoB);
    Instant inicioTomo = Instant.parse("2026-10-12T14:00:00Z");
    Instant inicioRaioX = Instant.parse("2026-10-12T16:00:00.123456Z");
    UUID idTomo = agendamento("TOMOGRAFIA", pacA, upa, equipamento, inicioTomo);
    UUID idRaioX = agendamento("RAIO_X", pacB, upa, equipamento, inicioRaioX);

    List<CandidatoAAviso> candidatos = candidatos(equipamento, DIA);

    assertThat(candidatos).hasSize(2);
    CandidatoAAviso tomo = candidatoDe(candidatos, idTomo);
    CandidatoAAviso raioX = candidatoDe(candidatos, idRaioX);
    assertThat(tomo.agendamento())
        .isEqualTo(
            new AgendamentoTomografia(idTomo, pacA, upa, equipamento, CRIADO_EM, inicioTomo));
    assertThat(tomo.dataNascimentoPaciente()).isEqualTo(nascimentoA);
    assertThat(raioX.agendamento())
        .isEqualTo(new AgendamentoRaioX(idRaioX, pacB, upa, equipamento, CRIADO_EM, inicioRaioX));
    assertThat(raioX.dataNascimentoPaciente()).isEqualTo(nascimentoB);
  }

  @Test
  void deveDevolverListaVaziaQuandoNaoHaAgendamentos() {
    assertThat(candidatos(UUID.randomUUID(), DIA)).isEmpty();
  }

  @Test
  void deveDescartarAgendamentoSemPacienteCadastradoSemDerrubarOsDemais() {
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    UUID completo = tomografia(pac, equipamento, "2026-10-12T15:00:00Z");
    UUID orfao = tomografia(UUID.randomUUID(), equipamento, "2026-10-12T16:00:00Z");

    AgendamentosDoDia dia = repositorio.buscarDoEquipamentoNoDia(equipamento, DIA);

    assertThat(ids(dia.candidatos())).containsExactly(completo);
    assertThat(dia.descartados())
        .containsExactly(new AgendamentoDescartado(orfao, Instant.parse("2026-10-12T16:00:00Z")));
  }

  @Test
  void deveDescartarAgendamentoDeTipoSemConstrutorMapeadoSemDerrubarOsDemais() {
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));

    // O CHECK do tipo impede gravar um tipo novo; o DDL do Postgres é transacional, então
    // derrubamos o CHECK só dentro de uma transação que é desfeita no fim.
    AgendamentosDoDia dia =
        transacao.execute(
            status -> {
              status.setRollbackOnly();
              jdbc.execute("ALTER TABLE agendamento_exame DROP CONSTRAINT ck_agendamento_tipo");
              UUID conhecido = tomografia(pac, equipamento, "2026-10-12T15:00:00Z");
              UUID desconhecido =
                  agendamento(
                      "ULTRASSOM",
                      pac,
                      UUID.randomUUID(),
                      equipamento,
                      Instant.parse("2026-10-12T17:00:00Z"));
              AgendamentosDoDia lido = repositorio.buscarDoEquipamentoNoDia(equipamento, DIA);
              assertThat(ids(lido.candidatos())).containsExactly(conhecido);
              assertThat(lido.descartados())
                  .containsExactly(
                      new AgendamentoDescartado(
                          desconhecido, Instant.parse("2026-10-12T17:00:00Z")));
              return lido;
            });

    assertThat(dia).isNotNull();
    // Desfeito: o CHECK voltou.
    assertThatThrownBy(
            () -> agendamento("ULTRASSOM", pac, UUID.randomUUID(), UUID.randomUUID(), CRIADO_EM))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deveUsarOHorarioDeVeraoAntigoDeSaoPauloNaJanelaDoDia() {
    // Em 2018-11-05 São Paulo ainda estava no horário de verão (UTC-2): o dia vai de
    // 2018-11-05T02:00Z (inclusive) a 2018-11-06T02:00Z (exclusive).
    UUID equipamento = UUID.randomUUID();
    UUID pac = paciente(LocalDate.of(1980, 1, 1));
    UUID ontem2330 = tomografia(pac, equipamento, "2018-11-05T01:30:00Z");
    UUID meiaNoite = tomografia(pac, equipamento, "2018-11-05T02:00:00Z");
    UUID hoje2330 = tomografia(pac, equipamento, "2018-11-06T01:30:00Z");
    UUID amanha0000 = tomografia(pac, equipamento, "2018-11-06T02:00:00Z");

    List<UUID> achados = ids(candidatos(equipamento, LocalDate.of(2018, 11, 5)));

    assertThat(achados)
        .containsExactlyInAnyOrder(meiaNoite, hoje2330)
        .doesNotContain(ontem2330, amanha0000);
  }

  @Test
  void deveManterOConjuntoDeTiposDoCheckIgualAoDoEnum() {
    String definicao =
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'ck_agendamento_tipo'",
            String.class);
    Set<String> noCheck = new HashSet<>();
    Matcher valores = Pattern.compile("'([A-Z_]+)'").matcher(definicao);
    while (valores.find()) {
      noCheck.add(valores.group(1));
    }
    Set<String> noEnum =
        Arrays.stream(TipoDeExameGravado.values()).map(Enum::name).collect(Collectors.toSet());

    assertThat(noCheck).isNotEmpty().isEqualTo(noEnum);
  }

  @Test
  void deveReconhecerSoOsTiposComConstrutorMapeado() {
    assertThat(TipoDeExameGravado.de("TOMOGRAFIA")).contains(TipoDeExameGravado.TOMOGRAFIA);
    assertThat(TipoDeExameGravado.de("RAIO_X")).contains(TipoDeExameGravado.RAIO_X);
    assertThat(TipoDeExameGravado.de("ULTRASSOM")).isEmpty();
    assertThat(TipoDeExameGravado.de(null)).isEmpty();
  }

  @Test
  void deveBuscarTodosOsCandidatosDoLoteComUmaUnicaConsulta() {
    UUID equipamento = UUID.randomUUID();
    UUID upa = UUID.randomUUID();
    for (int i = 0; i < 6; i++) {
      UUID pac = paciente(LocalDate.of(1970 + i, 5, 5));
      agendamento(
          i % 2 == 0 ? "TOMOGRAFIA" : "RAIO_X",
          pac,
          upa,
          equipamento,
          Instant.parse("2026-10-12T12:00:00Z").plusSeconds(i * 600L));
    }
    Statistics estatisticas = ligarEstatisticas();

    List<CandidatoAAviso> candidatos = candidatos(equipamento, DIA);

    assertThat(candidatos).hasSize(6);
    assertThat(estatisticas.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void deveTerOIndiceDeEquipamentoEInicioDoExame() {
    List<String> definicoes =
        jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'agendamento_exame'", String.class);

    assertThat(definicoes)
        .anySatisfy(
            def ->
                assertThat(def)
                    .contains("(equipamento_id, inicio_exame)")
                    .doesNotContain("UNIQUE"));
  }

  @Test
  void deveRejeitarTipoDeExameForaDaListaNoBanco() {
    assertThatThrownBy(
            () ->
                agendamento(
                    "ULTRASSOM",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    CRIADO_EM))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void naoDeveTerChaveEstrangeiraEntreOsAgregados() {
    Long chaves =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints"
                + " WHERE constraint_type = 'FOREIGN KEY'"
                + " AND table_name IN ('paciente', 'agendamento_exame')",
            Long.class);

    assertThat(chaves).isZero();
  }

  @Test
  void naoDeveExporDadoDePacienteNoToStringDaLinhaDeCandidato() {
    LinhaDeCandidato linha =
        new LinhaDeCandidato(
            UUID.randomUUID(),
            "TOMOGRAFIA",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            CRIADO_EM,
            Instant.parse("2026-10-12T15:00:00Z"),
            LocalDate.of(1944, 7, 8));

    assertThat(linha.toString()).doesNotContain("1944").doesNotContain("07-08");
  }

  private static CandidatoAAviso candidatoDe(List<CandidatoAAviso> candidatos, UUID id) {
    return candidatos.stream()
        .filter(c -> c.agendamento().id().equals(id))
        .findFirst()
        .orElseThrow();
  }
}
