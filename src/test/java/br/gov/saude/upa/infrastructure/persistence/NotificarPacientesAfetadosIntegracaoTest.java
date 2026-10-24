package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.AgendamentoRepository;
import br.gov.saude.upa.application.AgendamentosDoDia;
import br.gov.saude.upa.application.AlertasOperacionais;
import br.gov.saude.upa.application.AvisoPacienteRepository;
import br.gov.saude.upa.application.ConsultaDeEquipamento;
import br.gov.saude.upa.application.ContatoDePacienteRepository;
import br.gov.saude.upa.application.NotificadorEmail;
import br.gov.saude.upa.application.NotificadorSms;
import br.gov.saude.upa.application.NotificarPacientesAfetados;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.application.ResumoDoLote;
import br.gov.saude.upa.application.RevisaoManualRepository;
import br.gov.saude.upa.application.UPARepository;
import br.gov.saude.upa.domain.AgendamentoTomografia;
import br.gov.saude.upa.domain.AvisoPaciente;
import br.gov.saude.upa.domain.CandidatoAAviso;
import br.gov.saude.upa.domain.ContatoDoPaciente;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.MensagemDeAviso;
import br.gov.saude.upa.domain.PoliticaDeReserva;
import br.gov.saude.upa.domain.SelecaoDeAvisos;
import br.gov.saude.upa.domain.StatusAviso;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Caso de uso do lote contra o Postgres real: adapters reais de aviso e revisão manual; as demais
 * portas são fakes (os adapters delas vêm nas fatias 6 e 7). Fecha CA8, CA15 e CA16.
 */
class NotificarPacientesAfetadosIntegracaoTest extends PostgresIntegrationTest {

  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ID_TOMOGRAFO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final Instant MOMENTO_FALHA = Instant.parse("2026-10-11T15:00:00Z");
  private static final Instant AGORA = Instant.parse("2026-10-11T15:05:00Z");
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant PRIMEIRO_EXAME = Instant.parse("2026-10-11T18:00:00Z");
  private static final LocalDate NASCIMENTO = LocalDate.of(1990, 6, 15);
  private static final Duration TEMPO_LIMITE = Duration.ofSeconds(120);

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {

    @Bean
    FakeUpas fakeUpas() {
      return new FakeUpas();
    }

    @Bean
    FakeAgendamentos fakeAgendamentos() {
      return new FakeAgendamentos();
    }

    @Bean
    FakeContatos fakeContatos() {
      return new FakeContatos();
    }

    @Bean
    FakeCanal fakeSms() {
      return new FakeCanal();
    }

    @Bean
    FakeCanal fakeEmail() {
      return new FakeCanal();
    }

    @Bean
    FakeAlertas fakeAlertas() {
      return new FakeAlertas();
    }

    @Bean
    NotificarPacientesAfetados casoDeUsoDeTeste(
        FakeUpas upas,
        FakeAgendamentos agendamentos,
        FakeContatos contatos,
        FakeCanal fakeSms,
        FakeCanal fakeEmail,
        FakeAlertas alertas,
        AvisoPacienteRepository avisos,
        RevisaoManualRepository revisoes) {
      Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
      ConsultaDeEquipamento disponibilidade = id -> StatusEquipamento.INDISPONIVEL;
      return new NotificarPacientesAfetados(
          upas,
          agendamentos,
          contatos,
          disponibilidade,
          fakeSms,
          fakeEmail,
          avisos,
          revisoes,
          alertas,
          new SelecaoDeAvisos(relogio),
          new PoliticaDeReserva(),
          relogio,
          TEMPO_LIMITE,
          4);
    }
  }

  static class FakeUpas implements UPARepository {
    private final UPA upa =
        new UPA(
            ID_UPA,
            "Madureira",
            List.of(
                new Equipamento(
                    ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, StatusEquipamento.INDISPONIVEL)),
            Map.of(
                TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Cascadura, Rua A, 100")));

    @Override
    public Optional<UPA> buscar(UUID idUpa) {
      return idUpa.equals(ID_UPA) ? Optional.of(upa) : Optional.empty();
    }
  }

  static class FakeAgendamentos implements AgendamentoRepository {
    volatile List<CandidatoAAviso> candidatos = List.of();

    @Override
    public AgendamentosDoDia buscarDoEquipamentoNoDia(UUID idEquipamento, LocalDate dia) {
      return new AgendamentosDoDia(candidatos, List.of());
    }
  }

  static class FakeContatos implements ContatoDePacienteRepository {
    final Map<UUID, ContatoDoPaciente> porPaciente = new ConcurrentHashMap<>();

    @Override
    public Map<UUID, ContatoDoPaciente> buscarPorIds(Set<UUID> idsDePaciente) {
      return Map.copyOf(porPaciente);
    }
  }

  /** Canal falso: conta os envios por destino e esgota os destinos marcados. */
  static class FakeCanal implements NotificadorSms, NotificadorEmail {
    final Map<String, AtomicInteger> enviosPorDestino = new ConcurrentHashMap<>();
    final Set<String> esgotados = ConcurrentHashMap.newKeySet();

    @Override
    public ResultadoDoEnvio enviar(String destino, MensagemDeAviso mensagem) {
      enviosPorDestino.computeIfAbsent(destino, d -> new AtomicInteger()).incrementAndGet();
      return esgotados.contains(destino)
          ? new ResultadoDoEnvio.Esgotado()
          : new ResultadoDoEnvio.Confirmado();
    }

    int envios(String destino) {
      AtomicInteger contagem = enviosPorDestino.get(destino);
      return contagem == null ? 0 : contagem.get();
    }

    void limpar() {
      enviosPorDestino.clear();
      esgotados.clear();
    }
  }

  static class FakeAlertas implements AlertasOperacionais {
    final List<String> alertas = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void avisoIndeterminado(UUID eventoId, UUID agendamentoId) {
      alertas.add("indeterminado:" + agendamentoId);
    }

    @Override
    public void falhaInesperada(UUID eventoId, UUID agendamentoId, String tipoDoErro) {
      alertas.add("falha:" + agendamentoId);
    }

    @Override
    public void dadoInconsistente(UUID eventoId, UUID agendamentoId) {
      alertas.add("dadoInconsistente:" + agendamentoId);
    }
  }

  @Autowired private NotificarPacientesAfetados casoDeUso;
  @Autowired private AvisoPacienteRepository avisos;
  @Autowired private FakeAgendamentos agendamentos;
  @Autowired private FakeContatos contatos;
  @Autowired private FakeCanal fakeSms;
  @Autowired private FakeCanal fakeEmail;
  @Autowired private FakeAlertas alertas;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void limpar() {
    agendamentos.candidatos = List.of();
    contatos.porPaciente.clear();
    fakeSms.limpar();
    fakeEmail.limpar();
    alertas.alertas.clear();
  }

  // ---------- helpers ----------

  private static EquipamentoIndisponivelEvent novoEvento() {
    return new EquipamentoIndisponivelEvent(UUID.randomUUID(), ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA);
  }

  private static String telefone(CandidatoAAviso candidato) {
    return "tel-" + candidato.agendamento().id();
  }

  private static String emailDe(CandidatoAAviso candidato) {
    return "mail-" + candidato.agendamento().id();
  }

  /** {@code quantos} agendamentos de hoje, cada um com telefone e e-mail. */
  private List<CandidatoAAviso> prepararCandidatos(int quantos) {
    List<CandidatoAAviso> candidatos =
        IntStream.range(0, quantos)
            .mapToObj(
                i ->
                    new CandidatoAAviso(
                        new AgendamentoTomografia(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            ID_UPA,
                            ID_TOMOGRAFO,
                            CRIADO_EM,
                            PRIMEIRO_EXAME.plusSeconds(60L * i)),
                        NASCIMENTO))
            .toList();
    agendamentos.candidatos = candidatos;
    for (CandidatoAAviso c : candidatos) {
      contatos.porPaciente.put(
          c.agendamento().idPaciente(), new ContatoDoPaciente(telefone(c), emailDe(c)));
    }
    return candidatos;
  }

  private long contar(String tabela, UUID evento) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + tabela + " WHERE evento_id = ?", Long.class, evento);
  }

  private long contarAvisosComStatus(UUID evento, StatusAviso status) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM aviso_paciente WHERE evento_id = ? AND status = ?",
        Long.class,
        evento,
        status.name());
  }

  // ---------- CA8 ----------

  @Test
  void deveNaoReenviarNemDuplicarRevisaoManualQuandoEventoForReentregue() {
    EquipamentoIndisponivelEvent evento = novoEvento();
    List<CandidatoAAviso> candidatos = prepararCandidatos(3);
    CandidatoAAviso confirmavel = candidatos.get(0);
    CandidatoAAviso semContato = candidatos.get(1);
    CandidatoAAviso esgotado = candidatos.get(2);
    contatos.porPaciente.put(
        semContato.agendamento().idPaciente(), new ContatoDoPaciente(null, null));
    fakeSms.esgotados.add(telefone(esgotado));
    fakeEmail.esgotados.add(emailDe(esgotado));

    ResumoDoLote primeira = casoDeUso.executar(evento);
    ResumoDoLote reentrega = casoDeUso.executar(evento);

    assertThat(primeira).isEqualTo(new ResumoDoLote(3, 1, 0, 2, 0, 0, 0));
    assertThat(reentrega).isEqualTo(new ResumoDoLote(3, 0, 0, 0, 0, 3, 0));
    assertThat(fakeSms.envios(telefone(confirmavel))).isEqualTo(1);
    assertThat(fakeEmail.envios(emailDe(confirmavel))).isZero();
    assertThat(fakeSms.envios(telefone(esgotado))).isEqualTo(1);
    assertThat(fakeEmail.envios(emailDe(esgotado))).isEqualTo(1);
    assertThat(contar("revisao_manual", evento.eventoId())).isEqualTo(2);
    assertThat(contar("aviso_paciente", evento.eventoId())).isEqualTo(3);
    assertThat(contarAvisosComStatus(evento.eventoId(), StatusAviso.CONFIRMADO)).isEqualTo(1);
    assertThat(contarAvisosComStatus(evento.eventoId(), StatusAviso.PENDENTE)).isEqualTo(2);
  }

  // ---------- CA15 ----------

  @Test
  void deveEnviarUmaVezComExecucoesSimultaneasDoMesmoEvento() throws Exception {
    EquipamentoIndisponivelEvent evento = novoEvento();
    List<CandidatoAAviso> candidatos = prepararCandidatos(6);
    List<ResumoDoLote> resumos = Collections.synchronizedList(new ArrayList<>());

    Disputa.largarJuntas(
        5,
        i -> {
          resumos.add(casoDeUso.executar(evento));
          return true;
        });

    for (CandidatoAAviso c : candidatos) {
      assertThat(fakeSms.envios(telefone(c))).as("envios de %s", c).isEqualTo(1);
      assertThat(fakeEmail.envios(emailDe(c))).isZero();
    }
    assertThat(resumos).hasSize(5);
    assertThat(resumos.stream().mapToInt(ResumoDoLote::confirmados).sum()).isEqualTo(6);
    assertThat(resumos.stream().mapToInt(ResumoDoLote::falhasInesperadas).sum()).isZero();
    assertThat(contar("aviso_paciente", evento.eventoId())).isEqualTo(6);
    assertThat(contarAvisosComStatus(evento.eventoId(), StatusAviso.CONFIRMADO)).isEqualTo(6);
    assertThat(alertas.alertas).isEmpty();
  }

  // ---------- CA16 ----------

  @Test
  void deveReprocessarReservaPendenteAbandonada() {
    EquipamentoIndisponivelEvent evento = novoEvento();
    List<CandidatoAAviso> candidatos = prepararCandidatos(2);
    CandidatoAAviso abandonada = candidatos.get(0);
    CandidatoAAviso recente = candidatos.get(1);
    avisos.reservar(
        AvisoPaciente.reservar(
            evento.eventoId(), abandonada.agendamento().id(), AGORA.minus(Duration.ofMinutes(10))));
    avisos.reservar(
        AvisoPaciente.reservar(
            evento.eventoId(), recente.agendamento().id(), AGORA.minusSeconds(30)));

    ResumoDoLote resumo = casoDeUso.executar(evento);

    assertThat(resumo).isEqualTo(new ResumoDoLote(2, 1, 0, 0, 0, 1, 0));
    assertThat(fakeSms.envios(telefone(abandonada))).isEqualTo(1);
    assertThat(fakeSms.envios(telefone(recente))).isZero();
    Map<UUID, AvisoPaciente> gravados = avisos.buscarPorEvento(evento.eventoId());
    assertThat(gravados.get(abandonada.agendamento().id()).status())
        .isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(gravados.get(recente.agendamento().id()).status()).isEqualTo(StatusAviso.PENDENTE);
  }

  @Test
  void deveReassumirReservaAbandonadaComUmSoEnvioQuandoExecucoesSimultaneas() throws Exception {
    EquipamentoIndisponivelEvent evento = novoEvento();
    CandidatoAAviso abandonada = prepararCandidatos(1).get(0);
    avisos.reservar(
        AvisoPaciente.reservar(
            evento.eventoId(), abandonada.agendamento().id(), AGORA.minus(Duration.ofMinutes(10))));

    Disputa.largarJuntas(
        5,
        i -> {
          casoDeUso.executar(evento);
          return true;
        });

    assertThat(fakeSms.envios(telefone(abandonada))).isEqualTo(1);
    assertThat(contarAvisosComStatus(evento.eventoId(), StatusAviso.CONFIRMADO)).isEqualTo(1);
    assertThat(alertas.alertas).isEmpty();
  }
}
