package br.gov.saude.upa;

import static org.assertj.core.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * Procura dado de paciente (CA12) em eventos de log capturados. Cada dado sintético é registrado em
 * várias formas: nome inteiro e tokens raros (8+ letras) isolados; telefone comparado só pelos
 * dígitos, com e sem 55, com e sem DDD (9 e 8 dígitos finais), em qualquer pontuação; e-mail como
 * está e codificado em URL; nascimento ISO, {@code dd/MM/aaaa}, {@code dd-MM-aaaa} e {@code
 * aaaaMMdd}; horário do exame como instante, {@code aaaa-MM-dd'T'HH:mm}, {@code aaaa-MM-dd HH:mm}
 * (formato do {@code Timestamp}) e {@code HH:mm}, em São Paulo e em UTC. A busca ignora maiúsculas
 * e percorre tudo o que um evento carrega: mensagem crua e formatada, argumentos, throwable
 * (classe, mensagem, stack, causas e suprimidos), MDC, pares chave-valor, marcadores e nome da
 * thread.
 *
 * <p>Só os loggers da infraestrutura de TESTE ficam de fora (WireMock e Testcontainers, que
 * registram o corpo das requisições simuladas e a saída do container); os da aplicação, do Spring e
 * do Hibernate entram.
 */
public final class DetectorDeDadoSensivel {

  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
  private static final DateTimeFormatter BRASILEIRO = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter DATA_E_HORA =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
  private static final DateTimeFormatter DATA_ESPACO_HORA =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final DateTimeFormatter COM_HIFENS = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final DateTimeFormatter COMPACTO = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int TAMANHO_MINIMO_DO_TOKEN_DE_NOME = 8;

  /**
   * Trecho com cara de telefone: dígitos separados só por espaço, hífen, ponto, parênteses ou
   * {@code +}. Comparado só pelos dígitos, pega {@code (21) 98764-0001}, {@code 98764-0001} etc.
   */
  private static final Pattern TRECHO_NUMERICO = Pattern.compile("\\d[\\d\\s().+-]*\\d");

  /**
   * {@code HH:mm} sem dígito nem {@code :} logo antes ou depois: "às 18:37." conta; um instante
   * como {@code T15:00:00Z} ou {@code 12:00:01} não (na carga os exames cobrem quase todo minuto).
   */
  private static final Pattern HORA_ISOLADA = Pattern.compile("(?<![\\d:])\\d{2}:\\d{2}(?![\\d:])");

  /** Prefixos de logger da infraestrutura de teste (WireMock e Jetty embutido, Testcontainers). */
  private static final List<String> INFRAESTRUTURA_DE_TESTE =
      List.of(
          "com.github.tomakehurst.wiremock",
          "wiremock.",
          "org.wiremock",
          "org.testcontainers",
          "tc.",
          "com.github.dockerjava");

  /** Uma forma de um dado sensível, já em minúsculas, e o rótulo que o identifica. */
  private record Marcador(String rotulo, String valor) {}

  /** Onde, no evento, o dado foi achado. */
  public record Vazamento(String rotulo, String logger, String onde) {}

  private final Map<String, Marcador> marcadores = new LinkedHashMap<>();

  /** Telefones só pelos dígitos (com e sem 55, com e sem DDD). */
  private final Set<String> digitosDosTelefones = new LinkedHashSet<>();

  /** {@code HH:mm} dos exames (São Paulo e UTC): só contam isolados ({@link #HORA_ISOLADA}). */
  private final Set<String> horasDosExames = new java.util.HashSet<>();

  public DetectorDeDadoSensivel paciente(
      String nome, String telefone, String email, LocalDate nascimento) {
    marcador("nome", nome);
    if (nome != null) {
      // Sobrenome ou prenome raro isolado: tokens longos o bastante para não colidir com texto
      // comum.
      for (String token : nome.split("[^\\p{L}]+")) {
        if (token.length() >= TAMANHO_MINIMO_DO_TOKEN_DE_NOME) {
          marcador("nome", token);
        }
      }
    }
    if (telefone != null) {
      marcador("telefone", telefone);
      String digitos = telefone.replaceAll("\\D", "");
      digitosDeTelefone(digitos);
      if (digitos.startsWith("55")) {
        digitosDeTelefone(digitos.substring(2));
      }
      // Sem DDD: os 9 e os 8 dígitos finais.
      for (int finais : new int[] {9, 8}) {
        if (digitos.length() > finais) {
          digitosDeTelefone(digitos.substring(digitos.length() - finais));
        }
      }
    }
    marcador("email", email);
    if (email != null) {
      marcador("email", URLEncoder.encode(email, StandardCharsets.UTF_8));
    }
    if (nascimento != null) {
      marcador("nascimento", nascimento.toString());
      marcador("nascimento", BRASILEIRO.format(nascimento));
      marcador("nascimento", COM_HIFENS.format(nascimento));
      marcador("nascimento", COMPACTO.format(nascimento));
    }
    return this;
  }

  public DetectorDeDadoSensivel exame(String tipo, Instant inicio) {
    marcador("tipo do exame", tipo);
    if (inicio != null) {
      marcador("horario do exame", inicio.toString());
      for (ZoneId fuso : List.of(SAO_PAULO, ZoneOffset.UTC)) {
        marcador("horario do exame", DATA_E_HORA.format(inicio.atZone(fuso)));
        // Timestamp.toString(): espaço no lugar do T.
        marcador("horario do exame", DATA_ESPACO_HORA.format(inicio.atZone(fuso)));
        horasDosExames.add(HORA.format(inicio.atZone(fuso)));
      }
    }
    return this;
  }

  private void digitosDeTelefone(String digitos) {
    if (!digitos.isEmpty()) {
      digitosDosTelefones.add(digitos);
    }
  }

  private void marcador(String rotulo, String valor) {
    if (valor == null || valor.isBlank()) {
      return;
    }
    String normalizado = valor.toLowerCase(Locale.ROOT);
    marcadores.putIfAbsent(normalizado, new Marcador(rotulo, normalizado));
  }

  public static boolean ehInfraestruturaDeTeste(String logger) {
    return logger != null && INFRAESTRUTURA_DE_TESTE.stream().anyMatch(logger::startsWith);
  }

  public List<Vazamento> vazamentos(Collection<ILoggingEvent> eventos) {
    Set<Vazamento> achados = new LinkedHashSet<>();
    for (ILoggingEvent evento : eventos) {
      if (ehInfraestruturaDeTeste(evento.getLoggerName())) {
        continue;
      }
      for (Map.Entry<String, List<String>> trecho : trechos(evento).entrySet()) {
        for (String texto : trecho.getValue()) {
          procurar(texto, evento.getLoggerName(), trecho.getKey(), achados);
        }
      }
    }
    return List.copyOf(achados);
  }

  /** Falha com o rótulo, o logger e o lugar de cada vazamento (os dados são sintéticos). */
  public void exigirNenhumVazamento(Collection<ILoggingEvent> eventos) {
    List<Vazamento> achados = vazamentos(eventos);
    if (!achados.isEmpty()) {
      fail(
          "Dado de paciente em log ("
              + achados.size()
              + "): "
              + achados.stream().map(Vazamento::toString).collect(Collectors.joining("; ")));
    }
  }

  public int quantidadeDeMarcadores() {
    return marcadores.size();
  }

  private void procurar(String texto, String logger, String onde, Set<Vazamento> achados) {
    if (texto == null || texto.isEmpty()) {
      return;
    }
    String normalizado = texto.toLowerCase(Locale.ROOT);
    for (Marcador marcador : marcadores.values()) {
      if (normalizado.contains(marcador.valor())) {
        achados.add(new Vazamento(marcador.rotulo(), logger, onde));
      }
    }
    Matcher hora = HORA_ISOLADA.matcher(texto);
    while (hora.find()) {
      if (horasDosExames.contains(hora.group())) {
        achados.add(new Vazamento("horario do exame", logger, onde));
        break;
      }
    }
    if (digitosDosTelefones.isEmpty()) {
      return;
    }
    Matcher trecho = TRECHO_NUMERICO.matcher(texto);
    while (trecho.find()) {
      String digitos = trecho.group().replaceAll("\\D", "");
      for (String telefone : digitosDosTelefones) {
        if (digitos.contains(telefone)) {
          achados.add(new Vazamento("telefone", logger, onde));
          return;
        }
      }
    }
  }

  private static Map<String, List<String>> trechos(ILoggingEvent evento) {
    Map<String, List<String>> trechos = new LinkedHashMap<>();
    trechos.put("mensagem", listaDe(evento.getMessage(), evento.getFormattedMessage()));
    List<String> argumentos = new ArrayList<>();
    if (evento.getArgumentArray() != null) {
      for (Object argumento : evento.getArgumentArray()) {
        argumentos.add(textoDe(argumento));
      }
    }
    trechos.put("argumento", argumentos);
    List<String> throwable = new ArrayList<>();
    textosDoThrowable(
        evento.getThrowableProxy(), throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
    trechos.put("throwable", throwable);
    List<String> mdc = new ArrayList<>();
    Map<String, String> mapa = evento.getMDCPropertyMap();
    if (mapa != null) {
      mapa.forEach(
          (chave, valor) -> {
            mdc.add(chave);
            mdc.add(valor);
          });
    }
    trechos.put("mdc", mdc);
    List<String> pares = new ArrayList<>();
    List<KeyValuePair> chaveValor = evento.getKeyValuePairs();
    if (chaveValor != null) {
      for (KeyValuePair par : chaveValor) {
        pares.add(par.key);
        pares.add(textoDe(par.value));
      }
    }
    trechos.put("chave-valor", pares);
    List<String> nomesDeMarcador = new ArrayList<>();
    List<Marker> marcadoresDoEvento = evento.getMarkerList();
    if (marcadoresDoEvento != null) {
      for (Marker marker : marcadoresDoEvento) {
        nomesDeMarcador.add(marker.getName());
      }
    }
    trechos.put("marcador", nomesDeMarcador);
    trechos.put("thread", listaDe(evento.getThreadName()));
    return trechos;
  }

  private static void textosDoThrowable(
      IThrowableProxy proxy, List<String> textos, Set<IThrowableProxy> vistos) {
    if (proxy == null || !vistos.add(proxy)) {
      return;
    }
    textos.add(proxy.getClassName());
    textos.add(proxy.getMessage());
    StackTraceElementProxy[] pilha = proxy.getStackTraceElementProxyArray();
    if (pilha != null) {
      for (StackTraceElementProxy elemento : pilha) {
        textos.add(elemento.getSTEAsString());
      }
    }
    textosDoThrowable(proxy.getCause(), textos, vistos);
    IThrowableProxy[] suprimidos = proxy.getSuppressed();
    if (suprimidos != null) {
      for (IThrowableProxy suprimido : suprimidos) {
        textosDoThrowable(suprimido, textos, vistos);
      }
    }
  }

  private static String textoDe(Object valor) {
    if (valor instanceof Object[] lista) {
      return Arrays.deepToString(lista);
    }
    return String.valueOf(valor);
  }

  private static List<String> listaDe(String... textos) {
    List<String> lista = new ArrayList<>();
    for (String texto : textos) {
      if (texto != null) {
        lista.add(texto);
      }
    }
    return lista;
  }

  /**
   * Appender de captura no logger raiz. Fixa no momento do log o que o Logback calcularia depois
   * (mensagem formatada, nome da thread, MDC): o detector roda no fim, em outra thread.
   */
  public static final class Captura extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> eventos = new ArrayList<>();

    @Override
    protected void append(ILoggingEvent evento) {
      evento.prepareForDeferredProcessing();
      synchronized (eventos) {
        eventos.add(evento);
      }
    }

    public void ligarNaRaiz() {
      start();
      raiz().addAppender(this);
    }

    public void desligar() {
      raiz().detachAppender(this);
      stop();
    }

    public List<ILoggingEvent> eventos() {
      synchronized (eventos) {
        return List.copyOf(eventos);
      }
    }

    public void limpar() {
      synchronized (eventos) {
        eventos.clear();
      }
    }

    private static Logger raiz() {
      return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
  }
}
