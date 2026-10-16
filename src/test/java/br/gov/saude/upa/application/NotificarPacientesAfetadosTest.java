package br.gov.saude.upa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.domain.AgendamentoExame;
import br.gov.saude.upa.domain.AgendamentoTomografia;
import br.gov.saude.upa.domain.AvisoPaciente;
import br.gov.saude.upa.domain.CandidatoAAviso;
import br.gov.saude.upa.domain.ContatoDoPaciente;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.ItemDeRevisaoManual;
import br.gov.saude.upa.domain.MensagemDeAviso;
import br.gov.saude.upa.domain.MotivoCancelamento;
import br.gov.saude.upa.domain.MotivoRevisaoManual;
import br.gov.saude.upa.domain.PoliticaDeReserva;
import br.gov.saude.upa.domain.ResultadoNotificacao;
import br.gov.saude.upa.domain.SelecaoDeAvisos;
import br.gov.saude.upa.domain.StatusAviso;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

@Timeout(60)
class NotificarPacientesAfetadosTest {

  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID ID_TOMOGRAFO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID OUTRO_EVENTO = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

  /** 12:00 em São Paulo, dia 2026-10-11. */
  private static final Instant MOMENTO_FALHA = Instant.parse("2026-10-11T15:00:00Z");

  /** 12:05 em São Paulo. */
  private static final Instant AGORA = Instant.parse("2026-10-11T15:05:00Z");

  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant PRIMEIRO_EXAME = Instant.parse("2026-10-11T18:00:00Z");
  private static final LocalDate NASCIMENTO = LocalDate.of(1990, 6, 15);
  private static final Duration TEMPO_LIMITE = Duration.ofSeconds(120);
  private static final String SEGREDO = "dado-sensivel-do-paciente";

  private final UPARepository upas = mock(UPARepository.class);
  private final AgendamentoRepository agendamentos = mock(AgendamentoRepository.class);
  private final ContatoDePacienteRepository contatos = mock(ContatoDePacienteRepository.class);
  private final ConsultaDeEquipamento consulta = mock(ConsultaDeEquipamento.class);
  private final NotificadorSms sms = mock(NotificadorSms.class);
  private final NotificadorEmail email = mock(NotificadorEmail.class);
  private final AvisoPacienteRepository avisos = mock(AvisoPacienteRepository.class);
  private final RevisaoManualRepository revisoes = mock(RevisaoManualRepository.class);
  private final AlertasOperacionais alertas = mock(AlertasOperacionais.class);

  private final Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
  private final UPA upa =
      new UPA(
          ID_UPA,
          "Madureira",
          List.of(
              new Equipamento(
                  ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, StatusEquipamento.INDISPONIVEL)),
          Map.of(TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Cascadura, Rua A, 100")));
  private final EquipamentoIndisponivelEvent evento =
      new EquipamentoIndisponivelEvent(EVENTO, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA);

  private final Map<UUID, ContatoDoPaciente> contatosPorPaciente = new HashMap<>();
  private final List<ExecutorService> executores = new ArrayList<>();

  @BeforeEach
  void prepararPadroes() {
    lenient().when(upas.buscar(ID_UPA)).thenReturn(Optional.of(upa));
    lenient().when(avisos.buscarPorEvento(any())).thenReturn(Map.of());
    lenient().when(avisos.reservar(any())).thenReturn(true);
    lenient().when(avisos.reassumir(any(), any(), any(), any())).thenReturn(true);
    lenient().when(avisos.registrarDesfecho(any())).thenReturn(true);
    lenient().when(revisoes.agendamentosEmRevisao(any())).thenReturn(Set.of());
    lenient().when(revisoes.registrar(any())).thenReturn(true);
    lenient().when(consulta.statusAtual(any())).thenReturn(StatusEquipamento.INDISPONIVEL);
    lenient().when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Confirmado());
    lenient().when(email.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Confirmado());
    lenient().when(contatos.buscarPorIds(any())).thenReturn(contatosPorPaciente);
  }

  private ListAppender<ILoggingEvent> logCapturado;

  private void capturarLogs() {
    logCapturado = new ListAppender<>();
    logCapturado.start();
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NotificarPacientesAfetados.class))
        .addAppender(logCapturado);
  }

  @AfterEach
  void limpar() {
    if (logCapturado != null) {
      ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NotificarPacientesAfetados.class))
          .detachAppender(logCapturado);
    }
    Thread.interrupted();
    executores.forEach(ExecutorService::shutdownNow);
  }

  // ---------- helpers ----------

  private static UUID id(int numero) {
    return new UUID(0L, numero);
  }

  private static UUID paciente(int numero) {
    return id(1000 + numero);
  }

  private static String telefone(int numero) {
    return "tel-" + numero;
  }

  private static String emailDe(int numero) {
    return "mail-" + numero;
  }

  private static AgendamentoExame agendamento(int numero, Instant inicio) {
    return new AgendamentoTomografia(
        id(numero), paciente(numero), ID_UPA, ID_TOMOGRAFO, CRIADO_EM, inicio);
  }

  /** Candidato {@code numero}: o exame começa {@code numero} minutos depois das 15:00 (SP). */
  private static CandidatoAAviso candidato(int numero) {
    return new CandidatoAAviso(
        agendamento(numero, PRIMEIRO_EXAME.plusSeconds(60L * numero)), NASCIMENTO);
  }

  private void dadoCandidatos(CandidatoAAviso... candidatos) {
    when(agendamentos.buscarDoEquipamentoNoDia(ID_TOMOGRAFO, LocalDate.of(2026, 10, 11)))
        .thenReturn(List.of(candidatos));
    for (CandidatoAAviso c : candidatos) {
      int numero = (int) c.agendamento().id().getLeastSignificantBits();
      contatosPorPaciente.put(
          c.agendamento().idPaciente(), new ContatoDoPaciente(telefone(numero), emailDe(numero)));
    }
  }

  private void dadoContato(int numero, String telefone, String email) {
    contatosPorPaciente.put(paciente(numero), new ContatoDoPaciente(telefone, email));
  }

  private NotificarPacientesAfetados casoDeUso(int concorrencia) {
    return novo(TEMPO_LIMITE, concorrencia);
  }

  private NotificarPacientesAfetados novo(Duration tempoLimite, int concorrencia) {
    return new NotificarPacientesAfetados(
        upas,
        agendamentos,
        contatos,
        consulta,
        sms,
        email,
        avisos,
        revisoes,
        alertas,
        new SelecaoDeAvisos(relogio),
        new PoliticaDeReserva(),
        relogio,
        tempoLimite,
        concorrencia);
  }

  private ResumoDoLote executar() {
    return casoDeUso(1).executar(evento);
  }

  private List<AvisoPaciente> desfechosGravados(int vezes) {
    ArgumentCaptor<AvisoPaciente> captor = ArgumentCaptor.forClass(AvisoPaciente.class);
    verify(avisos, times(vezes)).registrarDesfecho(captor.capture());
    return captor.getAllValues();
  }

  private MensagemDeAviso mensagemEsperada(int numero) {
    return MensagemDeAviso.compor(
        upa, agendamento(numero, PRIMEIRO_EXAME.plusSeconds(60L * numero)));
  }

  private static AvisoPaciente avisoConfirmado() {
    AvisoPaciente aviso = AvisoPaciente.reservar(EVENTO, id(1), AGORA.minusSeconds(3600));
    aviso.aplicar(new ResultadoNotificacao.Enviada(), AGORA.minusSeconds(3000));
    return aviso;
  }

  private static AvisoPaciente avisoCancelado() {
    AvisoPaciente aviso = AvisoPaciente.reservar(EVENTO, id(1), AGORA.minusSeconds(3600));
    aviso.aplicar(
        new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO),
        AGORA.minusSeconds(3000));
    return aviso;
  }

  // ---------- fluxo feliz ----------

  @Test
  void deveReservarEnviarPorSmsEConfirmarQuandoCanalConfirmar() {
    dadoCandidatos(candidato(1));

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 1, 0, 0, 0, 0, 0));
    ArgumentCaptor<AvisoPaciente> reserva = ArgumentCaptor.forClass(AvisoPaciente.class);
    verify(avisos).reservar(reserva.capture());
    assertThat(reserva.getValue().eventoId()).isEqualTo(EVENTO);
    assertThat(reserva.getValue().agendamentoId()).isEqualTo(id(1));
    assertThat(reserva.getValue().reservadoEm()).isEqualTo(AGORA);
    verify(sms).enviar(telefone(1), mensagemEsperada(1));
    verify(email, never()).enviar(anyString(), any());
    AvisoPaciente desfecho = desfechosGravados(1).get(0);
    assertThat(desfecho.agendamentoId()).isEqualTo(id(1));
    assertThat(desfecho.status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(desfecho.atualizadoEm()).isEqualTo(AGORA);
    verify(revisoes, never()).registrar(any());
  }

  @Test
  void deveDevolverResumoZeradoQuandoNaoHaCandidatos() {
    dadoCandidatos();

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(0, 0, 0, 0, 0, 0, 0));
    verifyNoInteractions(sms, email);
    verify(avisos, never()).reservar(any());
  }

  @Test
  void deveIgnorarCandidatosForaDaSelecao() {
    CandidatoAAviso jaComecou =
        new CandidatoAAviso(agendamento(2, Instant.parse("2026-10-11T15:01:00Z")), NASCIMENTO);
    dadoCandidatos(candidato(1), jaComecou);

    ResumoDoLote resumo = executar();

    assertThat(resumo.selecionados()).isEqualTo(1);
    verify(sms, never()).enviar(eq(telefone(2)), any());
  }

  // ---------- CA5 ----------

  @Test
  void deveCairNoEmailQuandoSmsEsgotarTentativas() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 1, 0, 0, 0, 0, 0));
    verify(sms, times(1)).enviar(eq(telefone(1)), any());
    verify(email).enviar(emailDe(1), mensagemEsperada(1));
    assertThat(desfechosGravados(1).get(0).status()).isEqualTo(StatusAviso.CONFIRMADO);
    verify(revisoes, never()).registrar(any());
  }

  @Test
  void deveIrParaRevisaoManualQuandoSmsEEmailEsgotarem() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());
    when(email.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 1, 0, 0, 0));
    verify(revisoes)
        .registrar(
            new ItemDeRevisaoManual(EVENTO, id(1), MotivoRevisaoManual.CANAIS_ESGOTADOS, AGORA));
    verify(avisos, never()).registrarDesfecho(any());
    verify(alertas, never()).avisoIndeterminado(any(), any());
  }

  @Test
  void deveIrParaRevisaoManualQuandoSmsEsgotarEPacienteNaoTemEmail() {
    dadoCandidatos(candidato(1));
    dadoContato(1, telefone(1), null);
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());

    ResumoDoLote resumo = executar();

    assertThat(resumo.emRevisaoManual()).isEqualTo(1);
    verify(email, never()).enviar(anyString(), any());
    verify(revisoes)
        .registrar(
            new ItemDeRevisaoManual(EVENTO, id(1), MotivoRevisaoManual.CANAIS_ESGOTADOS, AGORA));
  }

  @Test
  void deveIrParaRevisaoManualQuandoSoTemEmailEEleEsgotar() {
    dadoCandidatos(candidato(1));
    dadoContato(1, null, emailDe(1));
    when(email.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());

    ResumoDoLote resumo = executar();

    assertThat(resumo.emRevisaoManual()).isEqualTo(1);
    verify(sms, never()).enviar(anyString(), any());
    verify(revisoes)
        .registrar(
            new ItemDeRevisaoManual(EVENTO, id(1), MotivoRevisaoManual.CANAIS_ESGOTADOS, AGORA));
  }

  // ---------- CA7 ----------

  @Test
  void deveUsarEmailQuandoPacienteNaoTemTelefone() {
    dadoCandidatos(candidato(1));
    dadoContato(1, null, emailDe(1));

    ResumoDoLote resumo = executar();

    assertThat(resumo.confirmados()).isEqualTo(1);
    verify(sms, never()).enviar(anyString(), any());
    verify(email).enviar(emailDe(1), mensagemEsperada(1));
  }

  @Test
  void deveRegistrarSemContatoSemChamarCanal() {
    dadoCandidatos(candidato(1));
    dadoContato(1, null, null);

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 1, 0, 0, 0));
    verifyNoInteractions(sms, email);
    verify(revisoes)
        .registrar(new ItemDeRevisaoManual(EVENTO, id(1), MotivoRevisaoManual.SEM_CONTATO, AGORA));
    verify(avisos, never()).registrarDesfecho(any());
  }

  @Test
  void deveTratarPacienteSemRegistroDeContatoComoSemContato() {
    dadoCandidatos(candidato(1));
    contatosPorPaciente.clear();

    ResumoDoLote resumo = executar();

    assertThat(resumo.emRevisaoManual()).isEqualTo(1);
    verifyNoInteractions(sms, email);
    verify(revisoes)
        .registrar(new ItemDeRevisaoManual(EVENTO, id(1), MotivoRevisaoManual.SEM_CONTATO, AGORA));
  }

  // ---------- CA6 ----------

  @Test
  void deveContinuarOLoteQuandoUmPacienteFalhar() {
    dadoCandidatos(candidato(1), candidato(2), candidato(3));
    when(sms.enviar(eq(telefone(2)), any())).thenThrow(new IllegalStateException(SEGREDO));

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(3, 2, 0, 0, 0, 0, 1));
    verify(alertas).falhaInesperada(EVENTO, id(2), "java.lang.IllegalStateException");
    List<UUID> comDesfecho =
        desfechosGravados(2).stream().map(AvisoPaciente::agendamentoId).toList();
    assertThat(comDesfecho).containsExactly(id(1), id(3));
  }

  @Test
  void naoDeveColocarAMensagemDaExcecaoNoAlerta() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenThrow(new IllegalStateException(SEGREDO));

    executar();

    ArgumentCaptor<String> tipo = ArgumentCaptor.forClass(String.class);
    verify(alertas).falhaInesperada(eq(EVENTO), eq(id(1)), tipo.capture());
    assertThat(tipo.getValue()).doesNotContain(SEGREDO);
  }

  @Test
  void deveContarAFalhaMesmoQuandoOAlertaFalhar() {
    dadoCandidatos(candidato(1), candidato(2));
    when(sms.enviar(eq(telefone(1)), any())).thenThrow(new IllegalStateException(SEGREDO));
    doThrow(new IllegalStateException(SEGREDO))
        .when(alertas)
        .falhaInesperada(any(), any(), anyString());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(2, 1, 0, 0, 0, 0, 1));
  }

  @Test
  void deveContarFalhaInesperadaQuandoODesfechoNaoForGravado() {
    dadoCandidatos(candidato(1));
    when(avisos.registrarDesfecho(any())).thenReturn(false);

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 0, 0, 1));
    verify(alertas).falhaInesperada(EVENTO, id(1), "DesfechoNaoGravado");
  }

  // ---------- CA9 ----------

  @Test
  void deveCancelarOsRestantesQuandoEquipamentoVoltarNoMeioDoLote() {
    dadoCandidatos(candidato(1), candidato(2), candidato(3));
    when(consulta.statusAtual(ID_TOMOGRAFO))
        .thenReturn(
            StatusEquipamento.INDISPONIVEL,
            StatusEquipamento.DISPONIVEL,
            StatusEquipamento.DISPONIVEL);

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(3, 1, 2, 0, 0, 0, 0));
    verify(consulta, times(3)).statusAtual(ID_TOMOGRAFO);
    verify(sms, times(1)).enviar(anyString(), any());
    verify(email, never()).enviar(anyString(), any());
    verify(revisoes, never()).registrar(any());
    List<AvisoPaciente> desfechos = desfechosGravados(3);
    assertThat(desfechos.get(0).status()).isEqualTo(StatusAviso.CONFIRMADO);
    assertThat(desfechos.get(1).status()).isEqualTo(StatusAviso.CANCELADO);
    assertThat(desfechos.get(1).motivoCancelamento())
        .contains(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);
    assertThat(desfechos.get(2).status()).isEqualTo(StatusAviso.CANCELADO);
  }

  @Test
  void deveContinuarEnviandoQuandoOEquipamentoEstiverEmManutencao() {
    dadoCandidatos(candidato(1));
    when(consulta.statusAtual(ID_TOMOGRAFO)).thenReturn(StatusEquipamento.EM_MANUTENCAO);

    ResumoDoLote resumo = executar();

    assertThat(resumo.confirmados()).isEqualTo(1);
    verify(sms).enviar(eq(telefone(1)), any());
  }

  // ---------- CA17 ----------

  @Test
  void deveEnviarDeNovoQuandoOEquipamentoCairDeNovoComOutroEvento() {
    dadoCandidatos(candidato(1));
    when(avisos.buscarPorEvento(EVENTO)).thenReturn(Map.of(id(1), avisoConfirmado()));
    EquipamentoIndisponivelEvent segundo =
        new EquipamentoIndisponivelEvent(OUTRO_EVENTO, ID_TOMOGRAFO, ID_UPA, MOMENTO_FALHA);

    ResumoDoLote primeiraEntrega = casoDeUso(1).executar(evento);
    ResumoDoLote segundaQueda = casoDeUso(1).executar(segundo);

    assertThat(primeiraEntrega.pulados()).isEqualTo(1);
    assertThat(segundaQueda).isEqualTo(new ResumoDoLote(1, 1, 0, 0, 0, 0, 0));
    verify(sms, times(1)).enviar(eq(telefone(1)), any());
    ArgumentCaptor<AvisoPaciente> reserva = ArgumentCaptor.forClass(AvisoPaciente.class);
    verify(avisos).reservar(reserva.capture());
    assertThat(reserva.getValue().eventoId()).isEqualTo(OUTRO_EVENTO);
  }

  // ---------- CA18 ----------

  @Test
  void deveManterPendenteEAlertarQuandoOCanalDevolverIndeterminado() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Indeterminado());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 1, 0, 0));
    verify(alertas).avisoIndeterminado(EVENTO, id(1));
    verify(email, never()).enviar(anyString(), any());
    verify(avisos, never()).registrarDesfecho(any());
    verify(revisoes, never()).registrar(any());
  }

  @Test
  void deveManterPendenteEAlertarQuandoOEmailDevolverIndeterminado() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Esgotado());
    when(email.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Indeterminado());

    ResumoDoLote resumo = executar();

    assertThat(resumo.indeterminados()).isEqualTo(1);
    verify(alertas).avisoIndeterminado(EVENTO, id(1));
    verify(avisos, never()).registrarDesfecho(any());
    verify(revisoes, never()).registrar(any());
  }

  // ---------- decisões de reserva ----------

  @TestFactory
  Stream<DynamicTest> naoDeveEnviarQuandoAvisoJaForConfirmadoCanceladoOuEmRevisao() {
    record Caso(String nome, Consumer<NotificarPacientesAfetadosTest> preparar) {}
    return Stream.of(
            new Caso(
                "confirmado",
                t ->
                    when(t.avisos.buscarPorEvento(EVENTO))
                        .thenReturn(Map.of(id(1), avisoConfirmado()))),
            new Caso(
                "cancelado",
                t ->
                    when(t.avisos.buscarPorEvento(EVENTO))
                        .thenReturn(Map.of(id(1), avisoCancelado()))),
            new Caso(
                "revisao",
                t -> when(t.revisoes.agendamentosEmRevisao(EVENTO)).thenReturn(Set.of(id(1)))))
        .map(
            caso ->
                DynamicTest.dynamicTest(
                    caso.nome(),
                    () -> {
                      NotificarPacientesAfetadosTest t = new NotificarPacientesAfetadosTest();
                      t.prepararPadroes();
                      t.dadoCandidatos(candidato(1));
                      caso.preparar().accept(t);

                      ResumoDoLote resumo = t.executar();

                      assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 0, 1, 0));
                      verifyNoInteractions(t.sms, t.email);
                      verify(t.avisos, never()).reservar(any());
                      verify(t.avisos, never()).reassumir(any(), any(), any(), any());
                    }));
  }

  @Test
  void devePularQuandoOutraExecucaoGanharAReserva() {
    dadoCandidatos(candidato(1));
    when(avisos.reservar(any())).thenReturn(false);

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 0, 1, 0));
    verifyNoInteractions(sms, email);
    verify(avisos, never()).registrarDesfecho(any());
  }

  @Test
  void deveReassumirReservaAbandonadaEEnviar() {
    dadoCandidatos(candidato(1));
    Instant reservadoEm = AGORA.minus(Duration.ofMinutes(10));
    when(avisos.buscarPorEvento(EVENTO))
        .thenReturn(Map.of(id(1), AvisoPaciente.reservar(EVENTO, id(1), reservadoEm)));

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 1, 0, 0, 0, 0, 0));
    verify(avisos).reassumir(EVENTO, id(1), reservadoEm, AGORA);
    verify(avisos, never()).reservar(any());
    verify(sms).enviar(eq(telefone(1)), any());
    assertThat(desfechosGravados(1).get(0).status()).isEqualTo(StatusAviso.CONFIRMADO);
  }

  @Test
  void devePularReservaPendenteDentroDoTempoLimite() {
    dadoCandidatos(candidato(1));
    when(avisos.buscarPorEvento(EVENTO))
        .thenReturn(Map.of(id(1), AvisoPaciente.reservar(EVENTO, id(1), AGORA.minusSeconds(30))));

    ResumoDoLote resumo = executar();

    assertThat(resumo.pulados()).isEqualTo(1);
    verify(avisos, never()).reassumir(any(), any(), any(), any());
    verifyNoInteractions(sms, email);
  }

  @Test
  void devePularQuandoOutraExecucaoAssumirAReserva() {
    dadoCandidatos(candidato(1));
    when(avisos.buscarPorEvento(EVENTO))
        .thenReturn(
            Map.of(
                id(1), AvisoPaciente.reservar(EVENTO, id(1), AGORA.minus(Duration.ofMinutes(10)))));
    when(avisos.reassumir(any(), any(), any(), any())).thenReturn(false);

    ResumoDoLote resumo = executar();

    assertThat(resumo.pulados()).isEqualTo(1);
    verifyNoInteractions(sms, email);
    verify(avisos, never()).registrarDesfecho(any());
  }

  // ---------- prioridade e concorrência ----------

  @Test
  void deveEnviarNaOrdemDaSelecaoQuandoAConcorrenciaForUm() {
    dadoCandidatos(candidato(3), candidato(1), candidato(5), candidato(2), candidato(4));
    List<String> ordem = Collections.synchronizedList(new ArrayList<>());
    when(sms.enviar(anyString(), any()))
        .thenAnswer(
            invocacao -> {
              ordem.add(invocacao.getArgument(0));
              return new ResultadoDoEnvio.Confirmado();
            });

    casoDeUso(1).executar(evento);

    assertThat(ordem)
        .containsExactly(telefone(1), telefone(2), telefone(3), telefone(4), telefone(5));
  }

  @Test
  void naoDeveExcederAConcorrenciaMaxima() throws Exception {
    int maximo = 3;
    dadoCandidatos(
        IntStream.rangeClosed(1, 10)
            .mapToObj(NotificarPacientesAfetadosTest::candidato)
            .toArray(CandidatoAAviso[]::new));
    AtomicInteger simultaneos = new AtomicInteger();
    AtomicInteger pico = new AtomicInteger();
    CountDownLatch chegaramAoLimite = new CountDownLatch(maximo);
    CountDownLatch passouDoLimite = new CountDownLatch(1);
    CountDownLatch liberar = new CountDownLatch(1);
    when(sms.enviar(anyString(), any()))
        .thenAnswer(
            invocacao -> {
              int agora = simultaneos.incrementAndGet();
              pico.accumulateAndGet(agora, Math::max);
              if (agora > maximo) {
                passouDoLimite.countDown();
              }
              chegaramAoLimite.countDown();
              liberar.await(30, TimeUnit.SECONDS);
              simultaneos.decrementAndGet();
              return new ResultadoDoEnvio.Confirmado();
            });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executores.add(executor);
    try {
      Future<ResumoDoLote> lote = executor.submit(() -> casoDeUso(maximo).executar(evento));

      assertThat(chegaramAoLimite.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(passouDoLimite.await(300, TimeUnit.MILLISECONDS)).isFalse();
      liberar.countDown();
      ResumoDoLote resumo = lote.get(60, TimeUnit.SECONDS);

      assertThat(pico.get()).isEqualTo(maximo);
      assertThat(resumo.confirmados()).isEqualTo(10);
    } finally {
      liberar.countDown();
    }
  }

  // ---------- anomalias ----------

  @Test
  void deveLancarQuandoAUpaNaoExistir() {
    when(upas.buscar(ID_UPA)).thenReturn(Optional.empty());
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);

    assertThatThrownBy(() -> casoDeUso.executar(evento))
        .isInstanceOf(UpaNaoEncontradaException.class)
        .hasMessageContaining(ID_UPA.toString());
    verifyNoInteractions(sms, email, agendamentos);
  }

  @Test
  void deveInterromperOLoteQuandoAThreadForInterrompida() {
    dadoCandidatos(candidato(1), candidato(2));
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> casoDeUso.executar(evento))
        .isInstanceOf(LoteInterrompidoException.class)
        .hasMessageContaining(EVENTO.toString());

    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verifyNoInteractions(sms, email);
  }

  @Test
  void deveRejeitarConfiguracaoEArgumentosInvalidos() {
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);

    assertThatThrownBy(() -> casoDeUso.executar(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> casoDeUso(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> novo(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> novo(Duration.ofSeconds(-1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> novo(null, 1)).isInstanceOf(NullPointerException.class);
  }

  // ---------- revisão dos revisores ----------

  @Test
  void deveLancarSemCausaNemMensagemDoErroQuandoUmErrorEscapar() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenThrow(new AssertionError("tel-123 segredo"));
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);

    Throwable erro = catchThrowable(() -> casoDeUso.executar(evento));

    assertThat(erro).isInstanceOf(IllegalStateException.class);
    assertThat(erro.getCause()).isNull();
    assertThat(erro.getMessage())
        .contains(EVENTO.toString())
        .contains("AssertionError")
        .doesNotContain("tel-123")
        .doesNotContain("segredo");
  }

  @Test
  void deveRelancarErroDaMaquinaVirtualComoEsta() {
    dadoCandidatos(candidato(1));
    StackOverflowError fatal = new StackOverflowError();
    when(sms.enviar(anyString(), any())).thenThrow(fatal);
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);

    Throwable erro = catchThrowable(() -> casoDeUso.executar(evento));

    assertThat(erro).isSameAs(fatal);
  }

  @Test
  void deveAlertarEnvioConfirmadoSemDesfechoQuandoOGravarLancar() {
    dadoCandidatos(candidato(1));
    when(avisos.registrarDesfecho(any())).thenThrow(new IllegalStateException(SEGREDO));

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 0, 0, 1));
    verify(alertas).falhaInesperada(EVENTO, id(1), "EnvioConfirmadoSemDesfecho");
    verify(alertas, times(1)).falhaInesperada(any(), any(), anyString());
    verify(sms, times(1)).enviar(anyString(), any());
  }

  @Test
  void deveManterIndeterminadoQuandoOAlertaDeIndeterminadoLancar() {
    dadoCandidatos(candidato(1));
    when(sms.enviar(anyString(), any())).thenReturn(new ResultadoDoEnvio.Indeterminado());
    doThrow(new IllegalStateException(SEGREDO)).when(alertas).avisoIndeterminado(any(), any());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(1, 0, 0, 0, 1, 0, 0));
    verify(alertas, never()).falhaInesperada(any(), any(), anyString());
  }

  @Test
  void deveBuscarContatosSoDosPacientesDaFilaSelecionada() {
    CandidatoAAviso jaComecou =
        new CandidatoAAviso(agendamento(2, Instant.parse("2026-10-11T15:01:00Z")), NASCIMENTO);
    dadoCandidatos(candidato(1), jaComecou);

    executar();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<UUID>> ids = ArgumentCaptor.forClass(Set.class);
    verify(contatos).buscarPorIds(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(paciente(1));
  }

  @Test
  void deveConsultarOsAgendamentosDoDiaDeSaoPauloEmVezDoDiaUtc() {
    dadoCandidatos(candidato(1));
    // 02:30 UTC do dia 12 ainda é o dia 11 em São Paulo.
    EquipamentoIndisponivelEvent madrugada =
        new EquipamentoIndisponivelEvent(
            EVENTO, ID_TOMOGRAFO, ID_UPA, Instant.parse("2026-10-12T02:30:00Z"));

    ResumoDoLote resumo = casoDeUso(1).executar(madrugada);

    verify(agendamentos).buscarDoEquipamentoNoDia(ID_TOMOGRAFO, LocalDate.of(2026, 10, 11));
    assertThat(resumo.selecionados()).isEqualTo(1);
  }

  @Test
  void deveInterromperOLoteQuandoAThreadForInterrompidaEsperandoUmEnvio() throws Exception {
    dadoCandidatos(candidato(1));
    CountDownLatch envioIniciado = new CountDownLatch(1);
    CountDownLatch liberar = new CountDownLatch(1);
    when(sms.enviar(anyString(), any()))
        .thenAnswer(
            invocacao -> {
              envioIniciado.countDown();
              liberar.await(30, TimeUnit.SECONDS);
              return new ResultadoDoEnvio.Confirmado();
            });
    AtomicReference<Throwable> erro = new AtomicReference<>();
    AtomicBoolean flagRestaurada = new AtomicBoolean();
    NotificarPacientesAfetados casoDeUso = casoDeUso(1);
    Thread chamadora =
        new Thread(
            () -> {
              try {
                casoDeUso.executar(evento);
              } catch (RuntimeException e) {
                erro.set(e);
              }
              flagRestaurada.set(Thread.currentThread().isInterrupted());
            });
    try {
      chamadora.start();
      assertThat(envioIniciado.await(30, TimeUnit.SECONDS)).isTrue();

      chamadora.interrupt();
      // A flag só cai quando a InterruptedException é entregue; o envio segue bloqueado até aqui.
      long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (chamadora.isInterrupted()) {
        assertThat(System.nanoTime()).isLessThan(limite);
        Thread.yield();
      }
      liberar.countDown();
      chamadora.join(30_000);

      assertThat(chamadora.isAlive()).isFalse();
      assertThat(erro.get()).isInstanceOf(LoteInterrompidoException.class);
      assertThat(flagRestaurada.get()).isTrue();
    } finally {
      liberar.countDown();
      Thread.interrupted();
    }
  }

  @Test
  void naoDeveRegistrarDadoDePacienteNosLogs() {
    capturarLogs();
    dadoCandidatos(candidato(1), candidato(2), candidato(3));
    when(sms.enviar(eq(telefone(1)), any()))
        .thenThrow(new IllegalStateException("tel-123 segredo"));
    when(sms.enviar(eq(telefone(2)), any())).thenReturn(new ResultadoDoEnvio.Indeterminado());
    when(avisos.registrarDesfecho(any())).thenReturn(false);

    executar();

    List<String> mensagens =
        logCapturado.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(mensagens).isNotEmpty();
    assertThat(mensagens)
        .noneMatch(m -> m.contains("tel-") || m.contains("mail-") || m.contains("segredo"));
    assertThat(mensagens).anyMatch(m -> m.contains("java.lang.IllegalStateException"));
    assertThat(logCapturado.list)
        .allMatch(e -> e.getThrowableProxy() == null, "sem exceção anexada ao log");
  }

  @Test
  void deveContinuarOLoteQuandoOSegundoPacienteEsgotarSmsEEmail() {
    dadoCandidatos(candidato(1), candidato(2), candidato(3));
    when(sms.enviar(eq(telefone(2)), any())).thenReturn(new ResultadoDoEnvio.Esgotado());
    when(email.enviar(eq(emailDe(2)), any())).thenReturn(new ResultadoDoEnvio.Esgotado());

    ResumoDoLote resumo = executar();

    assertThat(resumo).isEqualTo(new ResumoDoLote(3, 2, 0, 1, 0, 0, 0));
    verify(revisoes)
        .registrar(
            new ItemDeRevisaoManual(EVENTO, id(2), MotivoRevisaoManual.CANAIS_ESGOTADOS, AGORA));
    verify(sms).enviar(eq(telefone(3)), any());
  }

  @Test
  void deveRelerOStatusDoEquipamentoAntesDeCadaEnvio() {
    dadoCandidatos(candidato(1), candidato(2));

    executar();

    InOrder ordem = inOrder(consulta, sms);
    ordem.verify(consulta).statusAtual(ID_TOMOGRAFO);
    ordem.verify(sms).enviar(eq(telefone(1)), any());
    ordem.verify(consulta).statusAtual(ID_TOMOGRAFO);
    ordem.verify(sms).enviar(eq(telefone(2)), any());
  }
}
