package br.gov.saude.upa.infrastructure.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.saude.upa.DetectorDeDadoSensivel;
import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.ChaveDeIdempotencia;
import br.gov.saude.upa.application.DespachanteDoOutbox;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.NotificadorEmail;
import br.gov.saude.upa.application.NotificadorSms;
import br.gov.saude.upa.application.ResultadoDoEnvio;
import br.gov.saude.upa.domain.MensagemDeAviso;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Carga do lote (spec 001, CA14 e §13): 5.000 avisos com Postgres real e os dois canais no
 * WireMock, com os valores de PRODUÇÃO de resiliência e de lote (só as URLs mudam). Roda só com
 * {@code ./mvnw test -Pcarga}. É o único teste do projeto com tempo real: o critério é de tempo.
 *
 * <p>O relógio é o real, deslocado para 12:00 de São Paulo do dia corrente: anda de verdade (mede
 * os 5 minutos) e não depende da hora em que o teste roda. Cada {@code enviar()} dos dois canais é
 * medido por um decorador de teste em volta dos adapters de produção; o p95 do §13 vale para a
 * requisição HTTP ao provedor (timing do WireMock), e o {@code enviar()} tem o teto p99 &lt; 2 s
 * com zero negações do limitador (contra "Esgotado falso"). O relatório vai para {@code
 * target/carga/relatorio.txt} e para o log; a duração do lote é o insumo do lease do outbox.
 */
@Tag("carga")
class AvisoCargaIntegracaoTest extends PostgresIntegrationTest {

  private static final Logger LOG = LoggerFactory.getLogger(AvisoCargaIntegracaoTest.class);
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private static final int TOTAL = 5_000;

  /** Um a cada 10 pacientes só tem e-mail (~10%); os demais têm telefone e e-mail. */
  private static final int A_CADA_N_SO_EMAIL = 10;

  /** Uma a cada 50 mensagens de SMS (~2%) recebe 503 transitório na primeira tentativa. */
  private static final int A_CADA_N_UM_503 = 50;

  private static final int ATRASO_MINIMO_MS = 50;
  private static final int ATRASO_MAXIMO_MS = 150;

  private static final Duration PRAZO_DO_CA14 = Duration.ofMinutes(5);
  private static final double DESFECHO_MINIMO = 0.99;

  /** §13: p95 da requisição HTTP ao provedor (tempo de resposta por requisição, no WireMock). */
  private static final long P95_HTTP_MAXIMO_MS = 300;

  /**
   * Teto contra "Esgotado falso": p99 de {@code enviar()} (com retry e fila do limitador dentro)
   * abaixo de 2 s, folgado contra a espera de 5 s do limitador; e nenhum envio negado por ele.
   */
  private static final long P99_ENVIAR_MAXIMO_MS = 2_000;

  private static final double ERRO_MAXIMO = 0.01;

  /** Só para o teste não travar: bem acima do prazo do CA14. */
  private static final Duration PRAZO_MAXIMO_DO_TESTE = Duration.ofMinutes(20);

  private static final Instant MEIO_DIA =
      LocalDate.now(SAO_PAULO).atTime(12, 0).atZone(SAO_PAULO).toInstant();
  private static final Clock RELOGIO =
      Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), MEIO_DIA));

  private static final String ACEITE_SMS = "{\"id\":\"msg-1\",\"situacao\":\"ACEITA\"}";
  private static final String ACEITE_EMAIL = "{\"id\":\"mail-1\",\"situacao\":\"ACEITO\"}";
  private static final Path RELATORIO = Path.of("target", "carga", "relatorio.txt");

  private static final FalhaNaPrimeiraTentativa FALHAS_DO_SMS =
      new FalhaNaPrimeiraTentativa(A_CADA_N_UM_503);
  private static final WireMockServer SMS =
      new WireMockServer(
          WireMockConfiguration.options()
              .dynamicPort()
              .containerThreads(150)
              .extensions(FALHAS_DO_SMS));
  private static final WireMockServer EMAIL =
      new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(150));

  private static final Queue<Medicao> MEDICOES = new ConcurrentLinkedQueue<>();

  static {
    SMS.start();
    EMAIL.start();
  }

  @DynamicPropertySource
  static void canais(DynamicPropertyRegistry registro) {
    // Só as URLs mudam: resiliência e lote ficam com os valores de produção.
    registro.add("upa.canais.sms.base-url", SMS::baseUrl);
    registro.add("upa.canais.email.base-url", EMAIL::baseUrl);
  }

  /** Uma chamada a {@code enviar()} de um canal: duração e resultado. */
  private record Medicao(String canal, long nanos, String resultado) {}

  /**
   * 503 transitório POR MENSAGEM: uma de cada {@code periodo} mensagens, escolhida de forma
   * determinística pela {@code Idempotency-Key}, falha só na PRIMEIRA tentativa e é aceita na
   * repetição. Pela posição global da requisição, a mesma mensagem podia cair três vezes num
   * múltiplo e esgotar o canal por coincidência (teste intermitente).
   */
  static final class FalhaNaPrimeiraTentativa implements ResponseDefinitionTransformerV2 {

    private final int periodo;
    private final Set<String> jaFalharam = ConcurrentHashMap.newKeySet();
    private final AtomicInteger injetadas = new AtomicInteger();

    FalhaNaPrimeiraTentativa(int periodo) {
      this.periodo = periodo;
    }

    @Override
    public ResponseDefinition transform(ServeEvent evento) {
      ResponseDefinition original = evento.getResponseDefinition();
      String chave = evento.getRequest().getHeader("Idempotency-Key");
      if (chave == null
          || Math.floorMod(chave.hashCode(), periodo) != 0
          || !jaFalharam.add(chave)) {
        return original;
      }
      injetadas.incrementAndGet();
      return ResponseDefinitionBuilder.like(original).but().withStatus(503).withBody("").build();
    }

    @Override
    public String getName() {
      return "falha-na-primeira-tentativa";
    }
  }

  /** Mede cada {@code enviar()} do adapter de SMS de produção. */
  private record SmsMedido(NotificadorSms alvo) implements NotificadorSms {
    @Override
    public ResultadoDoEnvio enviar(
        String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave) {
      long inicio = System.nanoTime();
      ResultadoDoEnvio resultado = null;
      try {
        resultado = alvo.enviar(destino, mensagem, chave);
        return resultado;
      } finally {
        MEDICOES.add(new Medicao("sms", System.nanoTime() - inicio, nomeDe(resultado)));
      }
    }
  }

  /** Mede cada {@code enviar()} do adapter de e-mail de produção. */
  private record EmailMedido(NotificadorEmail alvo) implements NotificadorEmail {
    @Override
    public ResultadoDoEnvio enviar(
        String destino, MensagemDeAviso mensagem, ChaveDeIdempotencia chave) {
      long inicio = System.nanoTime();
      ResultadoDoEnvio resultado = null;
      try {
        resultado = alvo.enviar(destino, mensagem, chave);
        return resultado;
      } finally {
        MEDICOES.add(new Medicao("email", System.nanoTime() - inicio, nomeDe(resultado)));
      }
    }
  }

  private static String nomeDe(ResultadoDoEnvio resultado) {
    return resultado == null ? "Excecao" : resultado.getClass().getSimpleName();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RelogioEMedidores {

    @Bean
    @Primary
    Clock relogioRealDeslocado() {
      return RELOGIO;
    }

    /**
     * O lote recebe os adapters pelo NOME do bean ({@code @Qualifier}), então um {@code @Primary}
     * não seria usado: o decorador entra por um {@link BeanPostProcessor} em volta dos dois.
     */
    @Bean
    static BeanPostProcessor medidorDosCanais() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String nome) {
          if ("notificadorSmsHttp".equals(nome) && bean instanceof NotificadorSms sms) {
            return new SmsMedido(sms);
          }
          if ("notificadorEmailHttp".equals(nome) && bean instanceof NotificadorEmail email) {
            return new EmailMedido(email);
          }
          return bean;
        }
      };
    }
  }

  @AfterAll
  static void desligarProvedores() {
    SMS.stop();
    EMAIL.stop();
  }

  @Autowired private MarcarEquipamentoIndisponivel marcarIndisponivel;
  @Autowired private DespachanteDoOutbox despachante;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private AvisoProperties aviso;

  @Autowired
  @Qualifier("notificadorSmsHttp")
  private NotificadorSms smsDoLote;

  @Autowired
  @Qualifier("notificadorEmailHttp")
  private NotificadorEmail emailDoLote;

  private final DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();
  private final DetectorDeDadoSensivel detector = new DetectorDeDadoSensivel();
  private final UUID upa = UUID.randomUUID();
  private final UUID tomografo = UUID.randomUUID();
  private final List<UUID> pacientes = new ArrayList<>();
  private UUID eventoId;

  @AfterEach
  void limparOQueOTesteCriou() {
    captura.desligar();
    captura.limpar();
    if (eventoId != null) {
      jdbc.update("DELETE FROM aviso_paciente WHERE evento_id = ?", eventoId);
      jdbc.update("DELETE FROM revisao_manual WHERE evento_id = ?", eventoId);
      jdbc.update("DELETE FROM outbox_evento WHERE id = ?", eventoId);
    }
    jdbc.update("DELETE FROM agendamento_exame WHERE upa_id = ?", upa);
    jdbc.batchUpdate(
        "DELETE FROM paciente WHERE id = ?",
        pacientes.stream().map(id -> new Object[] {id}).toList());
    jdbc.update("DELETE FROM upa_endereco_alternativo WHERE upa_id = ?", upa);
    jdbc.update("DELETE FROM equipamento WHERE upa_id = ?", upa);
    jdbc.update("DELETE FROM upa WHERE id = ?", upa);
    pacientes.clear();
    MEDICOES.clear();
  }

  // ---------- massa sintética ----------

  private void criarMassa() {
    String sufixo = UUID.randomUUID().toString().substring(0, 6);
    jdbc.update("INSERT INTO upa (id, nome) VALUES (?, ?)", upa, "UPA Carga Sintetica");
    jdbc.update(
        "INSERT INTO equipamento (id, upa_id, tipo, status) VALUES (?, ?, 'TOMOGRAFO',"
            + " 'DISPONIVEL')",
        tomografo,
        upa);
    jdbc.update(
        "INSERT INTO upa_endereco_alternativo (upa_id, tipo, texto) VALUES (?, 'TOMOGRAFO', ?)",
        upa,
        "UPA Vizinha Sintetica, Rua Ficticia 1");

    List<Object[]> linhasDePaciente = new ArrayList<>(TOTAL);
    List<Object[]> linhasDeAgendamento = new ArrayList<>(TOTAL);
    Timestamp criadoEm = Timestamp.from(MEIO_DIA.minus(Duration.ofDays(10)));
    // Primeiro exame às 13:00 de São Paulo, um a cada 7 s (até ~22:43): todos depois do "agora".
    Instant primeiroExame = MEIO_DIA.plus(Duration.ofHours(1));
    for (int i = 0; i < TOTAL; i++) {
      UUID paciente = UUID.randomUUID();
      String nome = String.format(Locale.ROOT, "Carga Quixabeira %05d %s", i, sufixo);
      String telefone =
          i % A_CADA_N_SO_EMAIL == A_CADA_N_SO_EMAIL - 1
              ? null
              : String.format(Locale.ROOT, "+55219%08d", 30_000_000 + i);
      String email = String.format(Locale.ROOT, "carga.%05d.%s@exemplo.invalid", i, sufixo);
      LocalDate nascimento = LocalDate.of(1930, 1, 1).plusDays((i * 37L) % 27_000);
      linhasDePaciente.add(
          new Object[] {paciente, nome, telefone, email, java.sql.Date.valueOf(nascimento)});
      detector.paciente(nome, telefone, email, nascimento);
      pacientes.add(paciente);

      Instant inicio = primeiroExame.plusSeconds(7L * i);
      linhasDeAgendamento.add(
          new Object[] {
            UUID.randomUUID(), paciente, upa, tomografo, criadoEm, Timestamp.from(inicio)
          });
      detector.exame("TOMOGRAFIA", inicio);
    }
    jdbc.batchUpdate(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?, ?)",
        linhasDePaciente);
    jdbc.batchUpdate(
        "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id, criado_em,"
            + " inicio_exame) VALUES (?, 'TOMOGRAFIA', ?, ?, ?, ?, ?)",
        linhasDeAgendamento);
  }

  private void stubsDosProvedores() {
    SMS.resetAll();
    EMAIL.resetAll();
    SMS.stubFor(
        post(urlPathEqualTo("/v1/mensagens"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ACEITE_SMS)
                    .withUniformRandomDelay(ATRASO_MINIMO_MS, ATRASO_MAXIMO_MS)));
    EMAIL.stubFor(
        post(urlPathEqualTo("/v1/emails"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ACEITE_EMAIL)
                    .withUniformRandomDelay(ATRASO_MINIMO_MS, ATRASO_MAXIMO_MS)));
  }

  // ---------- amostragem do pool e da CPU (diagnóstico) ----------

  /** Amostra o pool do Hikari e a CPU durante o lote, para dizer onde está o tempo. */
  private static final class Amostrador implements AutoCloseable {

    private final ScheduledExecutorService agenda = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger amostras = new AtomicInteger();
    private final AtomicInteger maximoAguardando = new AtomicInteger();
    private final AtomicInteger maximoAtivas = new AtomicInteger();
    private final AtomicLong somaAguardando = new AtomicLong();
    private final AtomicLong somaAtivas = new AtomicLong();
    private final AtomicLong somaCpuDoSistemaPorMil = new AtomicLong();
    private final AtomicLong somaCpuDoProcessoPorMil = new AtomicLong();
    private final int tamanhoDoPool;

    Amostrador(HikariPoolMXBean pool, int tamanhoDoPool) {
      this.tamanhoDoPool = tamanhoDoPool;
      com.sun.management.OperatingSystemMXBean so =
          (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      agenda.scheduleAtFixedRate(
          () -> {
            int aguardando = pool.getThreadsAwaitingConnection();
            int ativas = pool.getActiveConnections();
            amostras.incrementAndGet();
            maximoAguardando.accumulateAndGet(aguardando, Math::max);
            maximoAtivas.accumulateAndGet(ativas, Math::max);
            somaAguardando.addAndGet(aguardando);
            somaAtivas.addAndGet(ativas);
            somaCpuDoSistemaPorMil.addAndGet(Math.round(Math.max(0, so.getCpuLoad()) * 1000));
            somaCpuDoProcessoPorMil.addAndGet(
                Math.round(Math.max(0, so.getProcessCpuLoad()) * 1000));
          },
          0,
          200,
          TimeUnit.MILLISECONDS);
    }

    List<String> linhas() {
      int n = Math.max(1, amostras.get());
      return List.of(
          String.format(
              Locale.ROOT,
              "Hikari: pool=%d, ativas media=%.1f max=%d, threads aguardando conexao media=%.1f"
                  + " max=%d (%d amostras)",
              tamanhoDoPool,
              somaAtivas.get() / (double) n,
              maximoAtivas.get(),
              somaAguardando.get() / (double) n,
              maximoAguardando.get(),
              amostras.get()),
          String.format(
              Locale.ROOT,
              "CPU: maquina media=%.0f%%, processo do teste (app + WireMock) media=%.0f%%,"
                  + " nucleos=%d",
              somaCpuDoSistemaPorMil.get() / (double) n / 10,
              somaCpuDoProcessoPorMil.get() / (double) n / 10,
              Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public void close() {
      agenda.shutdownNow();
    }
  }

  // ---------- consultas ----------

  private String situacaoDoEvento() {
    return jdbc.queryForObject(
        "SELECT situacao FROM outbox_evento WHERE id = ?", String.class, eventoId);
  }

  private long contar(String sql, Object... parametros) {
    Long n = jdbc.queryForObject(sql, Long.class, parametros);
    return n == null ? 0 : n;
  }

  private long comDesfechoAte(Instant limite) {
    return contar(
        "SELECT count(*) FROM agendamento_exame a WHERE a.upa_id = ? AND ("
            + " EXISTS (SELECT 1 FROM aviso_paciente v WHERE v.evento_id = ? AND v.agendamento_id"
            + " = a.id AND v.status = 'CONFIRMADO' AND v.atualizado_em <= ?)"
            + " OR EXISTS (SELECT 1 FROM revisao_manual r WHERE r.evento_id = ? AND"
            + " r.agendamento_id = a.id AND r.registrado_em <= ?))",
        upa,
        eventoId,
        Timestamp.from(limite),
        eventoId,
        Timestamp.from(limite));
  }

  private Instant ultimoDesfecho() {
    Timestamp ultimo =
        jdbc.queryForObject(
            "SELECT max(t) FROM (SELECT max(atualizado_em) AS t FROM aviso_paciente WHERE"
                + " evento_id = ? AND status <> 'PENDENTE' UNION ALL SELECT max(registrado_em)"
                + " FROM revisao_manual WHERE evento_id = ?) x",
            Timestamp.class,
            eventoId,
            eventoId);
    return ultimo == null ? null : ultimo.toInstant();
  }

  /**
   * Envios negados pelo limitador de taxa do canal: o adapter os devolve como {@code Esgotado}
   * (risco de "esgotado falso") e registra só o nome do canal. Contados pelo log da rodada.
   */
  private long negadosPeloLimitador(String canal) {
    String trecho = "Canal " + canal + " sem vaga no limitador de taxa";
    return captura.eventos().stream()
        .filter(e -> e.getLoggerName().endsWith(".ClienteDeCanal"))
        .filter(e -> e.getFormattedMessage().startsWith(trecho))
        .count();
  }

  /** Lista vazia falha: um percentil inventado passaria em qualquer teto. */
  private static long percentilMs(List<Long> ordenados, double percentil) {
    if (ordenados.isEmpty()) {
      throw new AssertionError("percentil " + percentil + " pedido sobre nenhuma medição");
    }
    int indice = (int) Math.ceil(percentil / 100.0 * ordenados.size()) - 1;
    return TimeUnit.NANOSECONDS.toMillis(
        ordenados.get(Math.min(Math.max(indice, 0), ordenados.size() - 1)));
  }

  private static List<Long> duracoes(List<Medicao> medicoes, String canal) {
    return medicoes.stream()
        .filter(m -> canal == null || m.canal().equals(canal))
        .map(Medicao::nanos)
        .sorted()
        .toList();
  }

  private static String linhaDeLatencia(String rotulo, List<Long> ordenados) {
    return String.format(
        Locale.ROOT,
        "%s: chamadas=%d p50=%d ms p95=%d ms p99=%d ms max=%d ms",
        rotulo,
        ordenados.size(),
        percentilMs(ordenados, 50),
        percentilMs(ordenados, 95),
        percentilMs(ordenados, 99),
        percentilMs(ordenados, 100));
  }

  /** Tempo de resposta de cada requisição HTTP ao provedor simulado (atraso + serviço), em ns. */
  private static List<Long> temposDoProvedor(WireMockServer... servidores) {
    List<Long> tempos = new ArrayList<>();
    for (WireMockServer servidor : servidores) {
      servidor.getAllServeEvents().stream()
          .map(e -> e.getTiming().getTotalTime())
          .filter(t -> t != null)
          .map(t -> TimeUnit.MILLISECONDS.toNanos(t))
          .forEach(tempos::add);
    }
    tempos.sort(null);
    return tempos;
  }

  /** Eventos do {@code ClienteDeCanal} com esta mensagem (formato do SLF4J, sem formatar). */
  private List<ILoggingEvent> doCanal(String formato) {
    return captura.eventos().stream()
        .filter(e -> e.getLoggerName().endsWith(".ClienteDeCanal"))
        .filter(e -> formato.equals(e.getMessage()))
        .filter(e -> e.getArgumentArray() != null)
        .toList();
  }

  /** Tentativas que falharam e foram repetidas pelo retry, por {@code canal:causa}. */
  private Map<String, Long> tentativasRepetidasPorCausa() {
    Map<String, Long> porCausa = new TreeMap<>();
    for (ILoggingEvent e : doCanal("Canal {} tentativa {} falhou e sera repetida: causa={}")) {
      Object[] argumentos = e.getArgumentArray();
      porCausa.merge(argumentos[0] + ":" + argumentos[2], 1L, Long::sum);
    }
    return porCausa;
  }

  /** Mudanças de estado do breaker por {@code canal:DE->PARA}. */
  private Map<String, Long> transicoesDoBreaker() {
    Map<String, Long> transicoes = new TreeMap<>();
    for (ILoggingEvent e : doCanal("Canal {} circuit breaker mudou de estado: {} -> {}")) {
      Object[] argumentos = e.getArgumentArray();
      transicoes.merge(argumentos[0] + ":" + argumentos[1] + "->" + argumentos[2], 1L, Long::sum);
    }
    return transicoes;
  }

  private static long aberturas(Map<String, Long> transicoes) {
    return transicoes.entrySet().stream()
        .filter(t -> t.getKey().endsWith("->OPEN"))
        .mapToLong(Map.Entry::getValue)
        .sum();
  }

  // ---------- teste ----------

  @Test
  @Timeout(value = 25, unit = TimeUnit.MINUTES)
  void ca14_deveDarDesfechoA99PorCentoDe5000AvisosEmAte5MinutosDentroDoOrcamentoDoAdapter()
      throws IOException, SQLException {
    assertThat(smsDoLote).as("o lote deve usar o SMS medido").isInstanceOf(SmsMedido.class);
    assertThat(emailDoLote).as("o lote deve usar o e-mail medido").isInstanceOf(EmailMedido.class);
    stubsDosProvedores();
    criarMassa();
    MEDICOES.clear();
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

    captura.ligarNaRaiz();
    eventoId = marcarIndisponivel.executar(upa, tomografo);
    Instant momentoFalha =
        jdbc.queryForObject(
                "SELECT ocorrido_em FROM outbox_evento WHERE id = ?", Timestamp.class, eventoId)
            .toInstant();

    long inicio = System.nanoTime();
    long prazo = inicio + PRAZO_MAXIMO_DO_TESTE.toNanos();
    int rodadas = 0;
    Map<String, Integer> errosDoDespachante = new TreeMap<>();
    try (Amostrador amostrador =
        new Amostrador(hikari.getHikariPoolMXBean(), hikari.getMaximumPoolSize())) {
      while (!"PROCESSADO".equals(situacaoDoEvento()) && System.nanoTime() < prazo) {
        rodadas++;
        try {
          if (despachante.despachar().reivindicados() == 0) {
            // Evento devolvido com atraso de reentrega: espera em tempo real (é o critério).
            Thread.sleep(1_000);
          }
        } catch (RuntimeException e) {
          errosDoDespachante.merge(e.getClass().getSimpleName(), 1, Integer::sum);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      long duracaoDoLoteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
      captura.desligar();
      List<String> diagnosticoDoAmbiente = amostrador.linhas();

      // ---------- medidas ----------
      String situacao = situacaoDoEvento();
      long cancelados =
          contar(
              "SELECT count(*) FROM aviso_paciente WHERE evento_id = ? AND status = 'CANCELADO'",
              eventoId);
      long naoCancelados = TOTAL - cancelados;
      long noPrazo = comDesfechoAte(momentoFalha.plus(PRAZO_DO_CA14));
      long comDesfecho = comDesfechoAte(Instant.parse("9999-12-31T00:00:00Z"));
      long confirmados =
          contar(
              "SELECT count(*) FROM aviso_paciente WHERE evento_id = ? AND status = 'CONFIRMADO'",
              eventoId);
      Map<String, Long> revisoesPorMotivo = new TreeMap<>();
      jdbc.query(
          "SELECT motivo, count(*) AS n FROM revisao_manual WHERE evento_id = ? GROUP BY motivo",
          rs -> {
            revisoesPorMotivo.put(rs.getString("motivo"), rs.getLong("n"));
          },
          eventoId);
      long pendentesSemRevisao =
          contar(
              "SELECT count(*) FROM aviso_paciente v WHERE v.evento_id = ? AND v.status ="
                  + " 'PENDENTE' AND NOT EXISTS (SELECT 1 FROM revisao_manual r WHERE"
                  + " r.evento_id = v.evento_id AND r.agendamento_id = v.agendamento_id)",
              eventoId);
      long semAviso =
          contar(
              "SELECT count(*) FROM agendamento_exame a WHERE a.upa_id = ? AND NOT EXISTS"
                  + " (SELECT 1 FROM aviso_paciente v WHERE v.evento_id = ? AND"
                  + " v.agendamento_id = a.id) AND NOT EXISTS (SELECT 1 FROM revisao_manual r"
                  + " WHERE r.evento_id = ? AND r.agendamento_id = a.id)",
              upa,
              eventoId,
              eventoId);
      // Erro do lote: avisos que terminam sem confirmação por falha de canal (canal esgotado ou
      // indeterminado/pendente). SEM_CONTATO e DADO_INCONSISTENTE não ocorrem nesta massa.
      long comErro =
          revisoesPorMotivo.getOrDefault("CANAIS_ESGOTADOS", 0L) + pendentesSemRevisao + semAviso;
      double taxaNoPrazo = naoCancelados == 0 ? 0 : noPrazo / (double) naoCancelados;
      double taxaDeErro = comErro / (double) TOTAL;

      List<Medicao> medicoes = List.copyOf(MEDICOES);
      List<Long> todas = duracoes(medicoes, null);
      long p99DoEnviar = percentilMs(todas, 99);
      List<Long> http = temposDoProvedor(SMS, EMAIL);
      long p95DoHttp = percentilMs(http, 95);
      Map<String, Long> repetidas = tentativasRepetidasPorCausa();
      Map<String, Long> transicoes = transicoesDoBreaker();
      Map<String, Long> resultadosDasChamadas = new TreeMap<>();
      medicoes.forEach(
          m -> resultadosDasChamadas.merge(m.canal() + ":" + m.resultado(), 1L, Long::sum));
      Instant ultimo = ultimoDesfecho();
      double vazao = todas.size() / Math.max(0.001, duracaoDoLoteMs / 1000.0);

      List<String> relatorio = new ArrayList<>();
      relatorio.add("== Carga do lote (spec 001, CA14 e paragrafo 13) ==");
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Lote: concorrencia-maxima=%d, tempo-limite-reserva=%s",
              aviso.concorrenciaMaxima(),
              aviso.tempoLimiteReserva()));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Massa: %d agendamentos, %d com telefone (SMS) e %d so com e-mail; provedor com"
                  + " atraso uniforme %d-%d ms; 503 so na primeira tentativa de 1 de cada %d"
                  + " mensagens de SMS, escolhidas pela Idempotency-Key"
                  + " (injetados=%d)",
              TOTAL,
              TOTAL - TOTAL / A_CADA_N_SO_EMAIL,
              TOTAL / A_CADA_N_SO_EMAIL,
              ATRASO_MINIMO_MS,
              ATRASO_MAXIMO_MS,
              A_CADA_N_UM_503,
              FALHAS_DO_SMS.injetadas.get()));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Evento: situacao=%s, rodadas do despachante=%d, erros do despachante=%s",
              situacao,
              rodadas,
              errosDoDespachante));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Duracao do lote (relogio de parede, do inicio do despacho ao PROCESSADO): %d ms"
                  + " (%.1f s)",
              duracaoDoLoteMs,
              duracaoDoLoteMs / 1000.0));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Ultimo desfecho gravado: %s s apos o momentoFalha",
              ultimo == null
                  ? "nenhum"
                  : String.format(
                      Locale.ROOT,
                      "%.1f",
                      Duration.between(momentoFalha, ultimo).toMillis() / 1000.0)));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "CA14: %d de %d nao cancelados com desfecho em ate 5 min = %.2f%% (minimo %.0f%%);"
                  + " com desfecho a qualquer tempo=%d; cancelados=%d",
              noPrazo,
              naoCancelados,
              taxaNoPrazo * 100,
              DESFECHO_MINIMO * 100,
              comDesfecho,
              cancelados));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Desfechos: confirmados=%d, revisao manual por motivo=%s, pendentes sem revisao"
                  + " (indeterminado)=%d, sem aviso=%d",
              confirmados,
              revisoesPorMotivo,
              pendentesSemRevisao,
              semAviso));
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Erro do lote (por aviso, nao por requisicao): %d de %d = %.2f%% (maximo %.0f%%)",
              comErro,
              TOTAL,
              taxaDeErro * 100,
              ERRO_MAXIMO * 100));
      relatorio.add(
          linhaDeLatencia(
              "Requisicao HTTP ao provedor (medida no servidor, WireMock, inclui o atraso"
                  + " injetado; sms + email; orcamento p95 <= "
                  + P95_HTTP_MAXIMO_MS
                  + " ms)",
              http));
      relatorio.add(
          linhaDeLatencia(
              "Por chamada a enviar() do adapter (sms + email, com retry e fila do limitador;"
                  + " teto p99 < "
                  + P99_ENVIAR_MAXIMO_MS
                  + " ms)",
              todas));
      relatorio.add(linhaDeLatencia("  so SMS", duracoes(medicoes, "sms")));
      relatorio.add(linhaDeLatencia("  so e-mail", duracoes(medicoes, "email")));
      relatorio.add("Resultados das chamadas: " + resultadosDasChamadas);
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Negados pelo limitador de taxa (informativo, contado pelo texto do log; a asercao"
                  + " e sobre os resultados Esgotado): sms=%d email=%d",
              negadosPeloLimitador("sms"),
              negadosPeloLimitador("email")));
      relatorio.add(
          String.format(Locale.ROOT, "Vazao: %.1f chamadas ao adapter por segundo de lote", vazao));
      relatorio.add("Tentativas que falharam e foram repetidas, por canal:causa: " + repetidas);
      relatorio.add(
          "Circuit breaker: aberturas="
              + aberturas(transicoes)
              + ", transicoes por canal:DE->PARA="
              + transicoes);
      relatorio.add(linhaDeLatencia("  requisicao HTTP so SMS", temposDoProvedor(SMS)));
      relatorio.add(linhaDeLatencia("  requisicao HTTP so e-mail", temposDoProvedor(EMAIL)));
      relatorio.addAll(diagnosticoDoAmbiente);
      relatorio.add(
          String.format(
              Locale.ROOT,
              "Log da rodada: %d eventos capturados no logger raiz, %d marcadores de dado"
                  + " sintetico procurados",
              captura.eventos().size(),
              detector.quantidadeDeMarcadores()));

      Files.createDirectories(RELATORIO.getParent());
      Files.write(RELATORIO, relatorio, StandardCharsets.UTF_8);
      relatorio.forEach(LOG::info);

      // ---------- asserções (todas reportadas juntas) ----------
      SoftAssertions criterios = new SoftAssertions();
      criterios.assertThat(situacao).as("evento processado").isEqualTo("PROCESSADO");
      criterios
          .assertThat(taxaNoPrazo)
          .as("CA14: fração dos não cancelados com desfecho em até 5 min")
          .isGreaterThanOrEqualTo(DESFECHO_MINIMO);
      criterios
          .assertThat(p95DoHttp)
          .as("§13: p95 da requisição HTTP ao provedor (ms)")
          .isLessThanOrEqualTo(P95_HTTP_MAXIMO_MS);
      criterios
          .assertThat(p99DoEnviar)
          .as("teto contra Esgotado falso: p99 de enviar() (ms)")
          .isLessThan(P99_ENVIAR_MAXIMO_MS);
      // Contra "Esgotado falso", pelo resultado (não pelo texto do log): nenhum canal esgotou e o
      // breaker não abriu.
      criterios
          .assertThat(resultadosDasChamadas.getOrDefault("sms:Esgotado", 0L))
          .as("teto contra Esgotado falso: chamadas de SMS com Esgotado")
          .isZero();
      criterios
          .assertThat(resultadosDasChamadas.getOrDefault("email:Esgotado", 0L))
          .as("teto contra Esgotado falso: chamadas de e-mail com Esgotado")
          .isZero();
      criterios
          .assertThat(aberturas(transicoes))
          .as("teto contra Esgotado falso: aberturas do circuit breaker")
          .isZero();
      // O retry foi de fato exercitado: cada 503 injetado virou uma tentativa repetida.
      criterios.assertThat(FALHAS_DO_SMS.injetadas.get()).as("503 injetados no SMS").isPositive();
      criterios
          .assertThat(repetidas.getOrDefault("sms:HTTP 503", 0L))
          .as("tentativas de SMS repetidas por HTTP 503 = 503 injetados")
          .isEqualTo((long) FALHAS_DO_SMS.injetadas.get());
      // A medição não pode ter buracos: um tempo por requisição recebida, uma medida por aviso.
      criterios
          .assertThat(http)
          .as("uma medição HTTP por requisição recebida pelo WireMock")
          .hasSize(SMS.getAllServeEvents().size() + EMAIL.getAllServeEvents().size());
      criterios
          .assertThat((long) todas.size())
          .as("medições de enviar() >= avisos não cancelados")
          .isGreaterThanOrEqualTo(naoCancelados);
      // A massa não quebrou em silêncio: nenhum paciente sem contato nem agendamento inconsistente.
      criterios
          .assertThat(revisoesPorMotivo.getOrDefault("SEM_CONTATO", 0L))
          .as("massa: SEM_CONTATO")
          .isZero();
      criterios
          .assertThat(revisoesPorMotivo.getOrDefault("DADO_INCONSISTENTE", 0L))
          .as("massa: DADO_INCONSISTENTE")
          .isZero();
      criterios.assertThat(taxaDeErro).as("§13: taxa de erro do lote").isLessThan(ERRO_MAXIMO);
      criterios
          .assertThat(detector.vazamentos(captura.eventos()))
          .as("CA12 em escala: dado de paciente no log da rodada")
          .isEmpty();
      criterios.assertAll();
    }
  }
}
