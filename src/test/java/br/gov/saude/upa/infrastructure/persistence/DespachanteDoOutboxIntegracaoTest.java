package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.AlertasDoOutbox;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.FilaDoOutbox;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import br.gov.saude.upa.application.ResumoDoDespacho;
import br.gov.saude.upa.application.ResumoDoLote;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Despachante com o outbox real e o lote simulado: o tempo vem de um relógio de teste, sem sleep.
 */
@Import(EscritaTestConfiguration.class)
class DespachanteDoOutboxIntegracaoTest extends PostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-11T15:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(150);
  private static final Duration ATRASO = Duration.ofSeconds(130);
  private static final ResumoDoLote SUCESSO = new ResumoDoLote(2, 2, 0, 0, 0, 0, 0);
  private static final ResumoDoLote COM_FALHA = new ResumoDoLote(2, 1, 0, 0, 0, 0, 1);

  @Autowired private OutboxRepositoryAdapter outbox;
  @Autowired private FilaDoOutbox fila;
  @Autowired private UPAPersistencia upas;
  @Autowired private MarcarEquipamentoIndisponivel marcarIndisponivel;
  @Autowired private MarcarEquipamentoDisponivel marcarDisponivel;
  @Autowired private JdbcTemplate jdbc;

  private final NotificarPacientesAfetados lote = mock(NotificarPacientesAfetados.class);
  private final AlertasDoOutbox alertas = mock(AlertasDoOutbox.class);
  private final RelogioMutavel relogio = new RelogioMutavel(T0);

  private final List<UUID> meusEventos = new ArrayList<>();

  /** Apaga só as linhas deste teste: o banco é compartilhado com as outras classes. */
  @AfterEach
  void limparOsMeusEventos() {
    meusEventos.forEach(id -> jdbc.update("DELETE FROM outbox_evento WHERE id = ?", id));
  }

  private DespachanteDoOutbox despachante(int maximoDeTentativas) {
    return new DespachanteDoOutbox(
        fila, lote, alertas, relogio, 10, LEASE, ATRASO, maximoDeTentativas);
  }

  private EquipamentoIndisponivelEvent gravarEvento() {
    var evento =
        new EquipamentoIndisponivelEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T0.minusSeconds(30));
    meusEventos.add(evento.eventoId());
    outbox.gravar(evento);
    return evento;
  }

  @Test
  void deveGerarNovoEventoENovoLoteQuandoEquipamentoCairDeNovo() {
    UUID idUpa = UUID.randomUUID();
    UUID tomografo = UUID.randomUUID();
    upas.salvar(
        new UPA(
            idUpa,
            "Madureira",
            List.of(
                new Equipamento(
                    tomografo, TipoEquipamento.TOMOGRAFO, StatusEquipamento.DISPONIVEL)),
            Map.of(TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Vizinha"))));
    List<EquipamentoIndisponivelEvent> executados = new ArrayList<>();
    when(lote.executar(any()))
        .thenAnswer(
            invocacao -> {
              executados.add(invocacao.getArgument(0));
              return SUCESSO;
            });
    DespachanteDoOutbox despachante = despachante(5);

    UUID primeiro = marcarIndisponivel.executar(idUpa, tomografo);
    meusEventos.add(primeiro);
    ResumoDoDespacho rodadaUm = despachante.despachar();
    marcarDisponivel.executar(idUpa, tomografo);
    UUID segundo = marcarIndisponivel.executar(idUpa, tomografo);
    meusEventos.add(segundo);
    ResumoDoDespacho rodadaDois = despachante.despachar();

    assertThat(rodadaUm).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    assertThat(rodadaDois).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    assertThat(situacao(primeiro)).isEqualTo("PROCESSADO");
    assertThat(situacao(segundo)).isEqualTo("PROCESSADO");
    assertThat(executados)
        .extracting(EquipamentoIndisponivelEvent::eventoId)
        .containsExactly(primeiro, segundo);
    assertThat(primeiro).isNotEqualTo(segundo);
  }

  @Test
  void deveEsgotarComAlertaALinhaIlegivelEProcessarAsDemais() {
    EquipamentoIndisponivelEvent bom = gravarEvento();
    UUID ilegivel = UUID.randomUUID();
    meusEventos.add(ilegivel);
    jdbc.update(
        "INSERT INTO outbox_evento (id, tipo, payload, ocorrido_em) VALUES (?, 'X',"
            + " CAST('{\"x\":1}' AS jsonb), ?)",
        ilegivel,
        java.sql.Timestamp.from(T0.minusSeconds(60)));
    when(lote.executar(any())).thenReturn(SUCESSO);

    ResumoDoDespacho resultado = despachante(5).despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(2, 1, 0, 1));
    assertThat(situacao(ilegivel)).isEqualTo("ESGOTADO");
    assertThat(situacao(bom.eventoId())).isEqualTo("PROCESSADO");
    verify(alertas).eventoEsgotado(ilegivel, 1, "PayloadIlegivel");
    verify(lote, times(1)).executar(any());
  }

  @Test
  void deveIniciarOLeaseDeCadaEventoSoQuandoElePassaARodarMesmoComOsAnterioresDemorando() {
    List<EquipamentoIndisponivelEvent> eventos = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      eventos.add(gravarEvento());
    }
    List<Boolean> leaseVigenteNoInicio = new ArrayList<>();
    when(lote.executar(any()))
        .thenAnswer(
            invocacao -> {
              EquipamentoIndisponivelEvent recebido = invocacao.getArgument(0);
              Instant ate =
                  jdbc.queryForObject(
                          "SELECT reivindicado_ate FROM outbox_evento WHERE id = ?",
                          java.sql.Timestamp.class,
                          recebido.eventoId())
                      .toInstant();
              leaseVigenteNoInicio.add(ate.isAfter(relogio.instant().plusSeconds(100)));
              relogio.avancar(Duration.ofSeconds(100));
              return SUCESSO;
            });

    ResumoDoDespacho resultado = despachante(5).despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(3, 3, 0, 0));
    assertThat(leaseVigenteNoInicio).containsExactly(true, true, true);
  }

  private String situacao(UUID eventoId) {
    return jdbc.queryForObject(
        "SELECT situacao FROM outbox_evento WHERE id = ?", String.class, eventoId);
  }

  @Test
  void deveProcessarOEventoUmaSoVezEMarcarProcessado() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    when(lote.executar(any())).thenReturn(SUCESSO);
    DespachanteDoOutbox despachante = despachante(5);

    ResumoDoDespacho primeiro = despachante.despachar();
    relogio.avancar(Duration.ofHours(1));
    ResumoDoDespacho segundo = despachante.despachar();

    assertThat(primeiro).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    assertThat(segundo).isEqualTo(new ResumoDoDespacho(0, 0, 0, 0));
    assertThat(situacao(evento.eventoId())).isEqualTo("PROCESSADO");
    verify(lote, times(1)).executar(any());
  }

  @Test
  void deveReentregarSoDepoisDe130sQuandoOLoteTeveFalhaInesperada() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    when(lote.executar(any())).thenReturn(COM_FALHA, SUCESSO);
    DespachanteDoOutbox despachante = despachante(5);

    ResumoDoDespacho falha = despachante.despachar();
    relogio.avancar(ATRASO.minusSeconds(1));
    ResumoDoDespacho cedo = despachante.despachar();
    relogio.avancar(Duration.ofSeconds(1));
    ResumoDoDespacho reentrega = despachante.despachar();

    assertThat(falha).isEqualTo(new ResumoDoDespacho(1, 0, 1, 0));
    assertThat(cedo).isEqualTo(new ResumoDoDespacho(0, 0, 0, 0));
    assertThat(reentrega).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    assertThat(situacao(evento.eventoId())).isEqualTo("PROCESSADO");
    verify(lote, times(2)).executar(any());
  }

  @Test
  void deveReentregarQuandoOCasoDeUsoLancaEGuardarSoOTipoDoErro() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    when(lote.executar(any())).thenThrow(new IllegalStateException("detalhe interno"));

    despachante(5).despachar();

    assertThat(situacao(evento.eventoId())).isEqualTo("PENDENTE");
    assertThat(
            jdbc.queryForObject(
                "SELECT tipo_ultimo_erro FROM outbox_evento WHERE id = ?",
                String.class,
                evento.eventoId()))
        .isEqualTo("IllegalStateException");
  }

  @Test
  void deveReentregarQuandoOLeaseExpiraPorQuedaDoProcessoSemContarComoProcessado() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    fila.reivindicar(T0, LEASE, 1);
    when(lote.executar(any())).thenReturn(SUCESSO);
    DespachanteDoOutbox despachante = despachante(5);

    relogio.avancar(LEASE.minusSeconds(1));
    ResumoDoDespacho comLeaseVigente = despachante.despachar();
    relogio.avancar(Duration.ofSeconds(1));
    ResumoDoDespacho comLeaseExpirado = despachante.despachar();

    assertThat(comLeaseVigente).isEqualTo(new ResumoDoDespacho(0, 0, 0, 0));
    assertThat(comLeaseExpirado).isEqualTo(new ResumoDoDespacho(1, 1, 0, 0));
    assertThat(situacao(evento.eventoId())).isEqualTo("PROCESSADO");
    assertThat(
            jdbc.queryForObject(
                "SELECT tentativas FROM outbox_evento WHERE id = ?",
                Integer.class,
                evento.eventoId()))
        .isEqualTo(2);
  }

  @Test
  void deveEsgotarEAlertarDepoisDoMaximoDeTentativas() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    when(lote.executar(any())).thenReturn(COM_FALHA);
    DespachanteDoOutbox despachante = despachante(2);

    despachante.despachar();
    relogio.avancar(ATRASO);
    ResumoDoDespacho ultima = despachante.despachar();
    relogio.avancar(Duration.ofDays(1));
    ResumoDoDespacho depois = despachante.despachar();

    assertThat(ultima).isEqualTo(new ResumoDoDespacho(1, 0, 0, 1));
    assertThat(depois).isEqualTo(new ResumoDoDespacho(0, 0, 0, 0));
    assertThat(situacao(evento.eventoId())).isEqualTo("ESGOTADO");
    verify(alertas, times(1)).eventoEsgotado(evento.eventoId(), 2, "FalhasInesperadasNoLote");
    verify(lote, times(2)).executar(any());
  }

  @Test
  void deveEsgotarSemExecutarQuandoOProcessoCaiuVariasVezesComOEvento() {
    EquipamentoIndisponivelEvent evento = gravarEvento();
    fila.reivindicar(T0, LEASE, 1);
    relogio.avancar(LEASE);
    fila.reivindicar(relogio.instant(), LEASE, 1);
    relogio.avancar(LEASE);

    ResumoDoDespacho resultado = despachante(2).despachar();

    assertThat(resultado).isEqualTo(new ResumoDoDespacho(1, 0, 0, 1));
    assertThat(situacao(evento.eventoId())).isEqualTo("ESGOTADO");
    verify(lote, never()).executar(any());
    verify(alertas).eventoEsgotado(evento.eventoId(), 3, "LeaseExpiradoVariasVezes");
  }

  @Test
  void deveProcessarCadaEventoUmaSoVezQuandoDoisDespachantesRodamJuntos() throws Exception {
    var eventos = IntStream.range(0, 16).mapToObj(i -> gravarEvento().eventoId()).toList();
    ConcurrentHashMap<UUID, AtomicInteger> execucoes = new ConcurrentHashMap<>();
    when(lote.executar(any()))
        .thenAnswer(
            invocacao -> {
              EquipamentoIndisponivelEvent recebido = invocacao.getArgument(0);
              execucoes
                  .computeIfAbsent(recebido.eventoId(), k -> new AtomicInteger())
                  .incrementAndGet();
              return SUCESSO;
            });
    DespachanteDoOutbox primeiro = despachante(5);
    DespachanteDoOutbox segundo = despachante(5);

    Disputa.largarJuntas(
        2,
        i -> {
          DespachanteDoOutbox despachante = i == 0 ? primeiro : segundo;
          ResumoDoDespacho rodada;
          do {
            rodada = despachante.despachar();
          } while (rodada.reivindicados() > 0);
          return true;
        });

    assertThat(execucoes.keySet()).containsExactlyInAnyOrderElementsOf(eventos);
    assertThat(execucoes.values()).allSatisfy(vezes -> assertThat(vezes.get()).isEqualTo(1));
    assertThat(eventos).allSatisfy(id -> assertThat(situacao(id)).isEqualTo("PROCESSADO"));
  }
}
