package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.EventoReivindicado;
import br.gov.saude.upa.application.FilaDoOutbox;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Outbox e fila de despacho no Postgres real: gravação, reivindicação exclusiva e lease. */
@Import(EscritaTestConfiguration.class)
class OutboxRepositoryTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(150);

  @Autowired private OutboxRepositoryAdapter outbox;
  @Autowired private FilaDoOutbox fila;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> meusEventos = new java.util.ArrayList<>();

  /** Apaga só as linhas deste teste: o banco é compartilhado com as outras classes. */
  @AfterEach
  void limparOsMeusEventos() {
    meusEventos.forEach(id -> jdbc.update("DELETE FROM outbox_evento WHERE id = ?", id));
  }

  private EquipamentoIndisponivelEvent gravarEvento(Instant momentoFalha) {
    var evento =
        new EquipamentoIndisponivelEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), momentoFalha);
    meusEventos.add(evento.eventoId());
    outbox.gravar(evento);
    return evento;
  }

  private List<EventoReivindicado> reivindicar(Instant agora, Duration lease, int limite) {
    return fila.reivindicar(agora, lease, limite).eventos();
  }

  private UUID gravarLinhaComPayload(String payload) {
    UUID id = UUID.randomUUID();
    meusEventos.add(id);
    jdbc.update(
        "INSERT INTO outbox_evento (id, tipo, payload, ocorrido_em) VALUES (?, 'X',"
            + " CAST(? AS jsonb), ?)",
        id,
        payload,
        java.sql.Timestamp.from(T0.minusSeconds(1)));
    return id;
  }

  @Test
  void deveEsgotarALinhaIlegivelSemTravarAsDemais() {
    EquipamentoIndisponivelEvent bom = gravarEvento(T0);
    UUID semCampos = gravarLinhaComPayload("{\"eventoId\":\"x\"}");
    UUID outroId = gravarLinhaComPayload("{\"x\":1}");
    UUID instanteRuim =
        gravarLinhaComPayload(
            "{\"eventoId\":null,\"idEquipamento\":null,\"idUpa\":null,\"momentoFalha\":\"ontem\"}");

    var reivindicacao = fila.reivindicar(T0, LEASE, 10);

    assertThat(reivindicacao.eventos())
        .extracting(r -> r.evento().eventoId())
        .containsExactly(bom.eventoId());
    assertThat(reivindicacao.ilegiveis())
        .extracting(br.gov.saude.upa.application.EventoIlegivel::eventoId)
        .containsExactlyInAnyOrder(semCampos, outroId, instanteRuim);
    for (UUID id : List.of(semCampos, outroId, instanteRuim)) {
      assertThat(linha(id).get("situacao")).isEqualTo("ESGOTADO");
      assertThat(linha(id).get("tipo_ultimo_erro")).isEqualTo("PayloadIlegivel");
    }
    assertThat(linha(bom.eventoId()).get("situacao")).isEqualTo("PENDENTE");
  }

  @Test
  void deveEsgotarQuandoOPayloadNaoBateComOIdDaLinha() {
    EquipamentoIndisponivelEvent outro = gravarEvento(T0);
    UUID id =
        gravarLinhaComPayload(
            "{\"eventoId\":\""
                + outro.eventoId()
                + "\",\"idEquipamento\":\""
                + UUID.randomUUID()
                + "\",\"idUpa\":\""
                + UUID.randomUUID()
                + "\",\"momentoFalha\":\"2026-10-11T15:00:00Z\"}");

    var reivindicacao = fila.reivindicar(T0, LEASE, 10);

    assertThat(reivindicacao.ilegiveis()).extracting(i -> i.eventoId()).containsExactly(id);
    assertThat(reivindicacao.eventos())
        .extracting(r -> r.evento().eventoId())
        .containsExactly(outro.eventoId());
  }

  private Map<String, Object> linha(UUID eventoId) {
    return jdbc.queryForMap(
        "SELECT tipo, CAST(payload AS text) AS payload, situacao, processado_em, reivindicado_ate,"
            + " tentativas, tipo_ultimo_erro, ocorrido_em FROM outbox_evento WHERE id = ?",
        eventoId);
  }

  private static Instant instante(Object timestamp) {
    return ((java.sql.Timestamp) timestamp).toInstant();
  }

  @Test
  void deveGravarOEventoPendenteSemTentativas() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);

    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("tipo")).isEqualTo("EquipamentoIndisponivelEvent");
    assertThat(linha.get("situacao")).isEqualTo("PENDENTE");
    assertThat(linha.get("tentativas")).isEqualTo(0);
    assertThat(linha.get("processado_em")).isNull();
    assertThat(linha.get("reivindicado_ate")).isNull();
    assertThat(instante(linha.get("ocorrido_em"))).isEqualTo(T0);
    assertThat((String) linha.get("payload")).contains(evento.eventoId().toString());
  }

  @Test
  void deveTruncarOMomentoAMicrossegundosAoGravar() {
    Instant comNanos = T0.plusNanos(123_456_789);

    EquipamentoIndisponivelEvent evento = gravarEvento(comNanos);

    assertThat(instante(linha(evento.eventoId()).get("ocorrido_em")))
        .isEqualTo(comNanos.truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void deveReivindicarContandoTentativaEFixandoOLease() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);

    List<EventoReivindicado> reivindicados = reivindicar(T0, LEASE, 10);

    assertThat(reivindicados).hasSize(1);
    assertThat(reivindicados.get(0).tentativas()).isEqualTo(1);
    assertThat(reivindicados.get(0).evento())
        .isEqualTo(
            new EquipamentoIndisponivelEvent(
                evento.eventoId(), evento.idEquipamento(), evento.idUpa(), T0));
    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("tentativas")).isEqualTo(1);
    assertThat(instante(linha.get("reivindicado_ate"))).isEqualTo(T0.plus(LEASE));
  }

  @Test
  void deveReconstituirOMomentoComMicrossegundosNoEventoReivindicado() {
    Instant comMicros = T0.plusNanos(123_456_000);
    gravarEvento(comMicros);

    assertThat(reivindicar(T0, LEASE, 10))
        .extracting(r -> r.evento().momentoFalha())
        .containsExactly(comMicros);
  }

  @Test
  void naoDeveReivindicarEventoComLeaseVigenteMasDeveDepoisDeExpirar() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);
    reivindicar(T0, LEASE, 10);

    assertThat(reivindicar(T0.plus(LEASE).minusMillis(1), LEASE, 10)).isEmpty();

    List<EventoReivindicado> reentregues = reivindicar(T0.plus(LEASE), LEASE, 10);
    assertThat(reentregues).hasSize(1);
    assertThat(reentregues.get(0).evento().eventoId()).isEqualTo(evento.eventoId());
    assertThat(reentregues.get(0).tentativas()).isEqualTo(2);
  }

  @Test
  void deveRespeitarOLimiteEAOrdemDeOcorrencia() {
    EquipamentoIndisponivelEvent segundo = gravarEvento(T0.plusSeconds(20));
    EquipamentoIndisponivelEvent primeiro = gravarEvento(T0.plusSeconds(10));
    EquipamentoIndisponivelEvent terceiro = gravarEvento(T0.plusSeconds(30));

    List<EventoReivindicado> lote = reivindicar(T0.plusSeconds(60), LEASE, 2);

    assertThat(lote)
        .extracting(r -> r.evento().eventoId())
        .containsExactly(primeiro.eventoId(), segundo.eventoId());
    assertThat(reivindicar(T0.plusSeconds(60), LEASE, 5))
        .extracting(r -> r.evento().eventoId())
        .containsExactly(terceiro.eventoId());
  }

  @Test
  void deveEntregarCadaEventoAUmSoDespachanteQuandoVariosReivindicamJuntos() throws Exception {
    Set<UUID> gravados =
        IntStream.range(0, 24)
            .mapToObj(i -> gravarEvento(T0.plusSeconds(i)).eventoId())
            .collect(Collectors.toSet());
    ConcurrentLinkedQueue<UUID> reivindicados = new ConcurrentLinkedQueue<>();

    Disputa.largarJuntas(
        4,
        i -> {
          List<EventoReivindicado> lote;
          do {
            lote = reivindicar(T0.plusSeconds(100), LEASE, 3);
            lote.forEach(r -> reivindicados.add(r.evento().eventoId()));
          } while (!lote.isEmpty());
          return true;
        });

    assertThat(reivindicados).hasSize(24).doesNotHaveDuplicates();
    assertThat(Set.copyOf(reivindicados)).isEqualTo(gravados);
  }

  @Test
  void deveDarOEventoUnicoAUmSoDespachanteQuandoOitoDisputam() throws Exception {
    gravarEvento(T0);

    List<Boolean> resultados =
        Disputa.largarJuntas(8, i -> !reivindicar(T0.plusSeconds(1), LEASE, 1).isEmpty());

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
  }

  @Test
  void deveMarcarProcessadoENuncaMaisReivindicar() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);
    EventoReivindicado reivindicado = reivindicar(T0, LEASE, 1).get(0);

    boolean marcado = fila.marcarProcessado(reivindicado, T0.plusSeconds(5));

    assertThat(marcado).isTrue();
    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("situacao")).isEqualTo("PROCESSADO");
    assertThat(instante(linha.get("processado_em"))).isEqualTo(T0.plusSeconds(5));
    assertThat(reivindicar(T0.plus(Duration.ofDays(1)), LEASE, 10)).isEmpty();
  }

  @Test
  void deveDevolverComAtrasoERegistrarSoOTipoDoErro() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);
    EventoReivindicado reivindicado = reivindicar(T0, LEASE, 1).get(0);
    Instant reentrega = T0.plusSeconds(130);

    boolean devolvido = fila.devolver(reivindicado, reentrega, "IllegalStateException");

    assertThat(devolvido).isTrue();
    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("situacao")).isEqualTo("PENDENTE");
    assertThat(linha.get("tipo_ultimo_erro")).isEqualTo("IllegalStateException");
    assertThat(reivindicar(reentrega.minusMillis(1), LEASE, 1)).isEmpty();
    assertThat(reivindicar(reentrega, LEASE, 1))
        .extracting(EventoReivindicado::tentativas)
        .containsExactly(2);
  }

  @Test
  void deveTruncarEm200CaracteresOTipoDoErroAoDevolverEAoEsgotar() {
    String longo = "E".repeat(300);
    EquipamentoIndisponivelEvent primeiro = gravarEvento(T0);
    EventoReivindicado reivindicadoA = reivindicar(T0, LEASE, 1).get(0);
    assertThat(fila.devolver(reivindicadoA, T0.plusSeconds(130), longo)).isTrue();

    EquipamentoIndisponivelEvent segundo = gravarEvento(T0.plusSeconds(1));
    EventoReivindicado reivindicadoB =
        reivindicar(T0.plusSeconds(1), LEASE, 5).stream()
            .filter(r -> r.evento().eventoId().equals(segundo.eventoId()))
            .findFirst()
            .orElseThrow();
    assertThat(fila.esgotar(reivindicadoB, longo)).isTrue();

    assertThat(linha(primeiro.eventoId()).get("tipo_ultimo_erro")).isEqualTo("E".repeat(200));
    assertThat(linha(segundo.eventoId()).get("tipo_ultimo_erro")).isEqualTo("E".repeat(200));
  }

  @Test
  void deveEsgotarENuncaMaisReivindicar() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);
    EventoReivindicado reivindicado = reivindicar(T0, LEASE, 1).get(0);

    boolean esgotado = fila.esgotar(reivindicado, "FalhasInesperadasNoLote");

    assertThat(esgotado).isTrue();
    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("situacao")).isEqualTo("ESGOTADO");
    assertThat(linha.get("tipo_ultimo_erro")).isEqualTo("FalhasInesperadasNoLote");
    assertThat(reivindicar(T0.plus(Duration.ofDays(1)), LEASE, 10)).isEmpty();
  }

  @Test
  void deveIgnorarOFimDeQuemPerdeuOLeaseParaOutroDespachante() {
    EquipamentoIndisponivelEvent evento = gravarEvento(T0);
    EventoReivindicado primeiro = reivindicar(T0, LEASE, 1).get(0);
    Instant depoisDoLease = T0.plus(LEASE).plusSeconds(1);
    EventoReivindicado segundo = reivindicar(depoisDoLease, LEASE, 1).get(0);

    assertThat(fila.marcarProcessado(primeiro, depoisDoLease)).isFalse();
    assertThat(fila.devolver(primeiro, depoisDoLease, "X")).isFalse();
    assertThat(fila.esgotar(primeiro, "X")).isFalse();

    Map<String, Object> linha = linha(evento.eventoId());
    assertThat(linha.get("situacao")).isEqualTo("PENDENTE");
    assertThat(linha.get("tentativas")).isEqualTo(2);
    assertThat(instante(linha.get("reivindicado_ate"))).isEqualTo(depoisDoLease.plus(LEASE));
    assertThat(fila.marcarProcessado(segundo, depoisDoLease)).isTrue();
  }

  @Test
  void deveTerIndiceParcialSobreOsNaoProcessados() {
    String definicao =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_outbox_nao_processado'",
            String.class);

    assertThat(definicao).contains("WHERE").contains("PENDENTE");
  }
}
