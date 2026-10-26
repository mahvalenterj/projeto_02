package br.gov.saude.upa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * O detector do CA12 precisa pegar o vazamento em cada forma que um evento de log pode carregar; um
 * detector que nunca acusa nada faria o teste de privacidade passar sem provar coisa alguma. Os
 * vazamentos são PLANTADOS aqui com dados sintéticos.
 */
class DetectorDeDadoSensivelTest {

  private static final String NOME = "Florisbela Quixabeira Sintetica";
  private static final String TELEFONE = "+5521987600123";
  private static final String EMAIL = "florisbela.sintetica@exemplo.invalid";
  private static final LocalDate NASCIMENTO = LocalDate.of(1941, 3, 7);
  // 21:37 UTC = 18:37 em America/Sao_Paulo.
  private static final Instant INICIO_EXAME = Instant.parse("2026-10-11T21:37:00Z");
  private static final UUID EVENTO = UUID.fromString("00000000-0000-0000-0000-00000000e009");
  private static final UUID AGENDAMENTO = UUID.fromString("00000000-0000-0000-0000-00000000a009");

  private final Logger aplicacao = LoggerFactory.getLogger("br.gov.saude.upa.teste.Detector");
  private final DetectorDeDadoSensivel detector =
      new DetectorDeDadoSensivel()
          .paciente(NOME, TELEFONE, EMAIL, NASCIMENTO)
          .exame("TOMOGRAFIA", INICIO_EXAME);
  private final DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();

  @BeforeEach
  void capturar() {
    captura.ligarNaRaiz();
  }

  @AfterEach
  void soltar() {
    captura.desligar();
    MDC.clear();
  }

  private List<DetectorDeDadoSensivel.Vazamento> vazamentos() {
    return detector.vazamentos(captura.eventos());
  }

  private void deveAcusar(String rotulo, String onde) {
    assertThat(vazamentos())
        .as("o vazamento plantado (%s em %s) tem de ser pego", rotulo, onde)
        .anySatisfy(
            v -> {
              assertThat(v.rotulo()).isEqualTo(rotulo);
              assertThat(v.onde()).isEqualTo(onde);
            });
  }

  @Test
  void deveAcusarNomeNaMensagemFormatada() {
    aplicacao.warn("Aviso do paciente {}", NOME);

    deveAcusar("nome", "mensagem");
  }

  @Test
  void deveAcusarTelefoneEmArgumentoQueNaoApareceNaMensagem() {
    // Sem placeholder: a mensagem formatada fica limpa, mas o argumento vai junto no evento.
    aplicacao.warn("Envio por SMS", TELEFONE);

    deveAcusar("telefone", "argumento");
  }

  @Test
  void deveAcusarTelefoneSemOSinalDeMais() {
    aplicacao.warn("destino={}", TELEFONE.substring(1));

    deveAcusar("telefone", "mensagem");
  }

  @Test
  void deveAcusarEmailEmMaiusculasNaMensagem() {
    aplicacao.warn("destino={}", EMAIL.toUpperCase(java.util.Locale.ROOT));

    deveAcusar("email", "mensagem");
  }

  @Test
  void deveAcusarNascimentoNaMensagemDoThrowable() {
    aplicacao.error("Falha", new IllegalStateException("nascido em " + NASCIMENTO));

    deveAcusar("nascimento", "throwable");
  }

  @Test
  void deveAcusarNascimentoNoFormatoBrasileiroNaCausaDoThrowable() {
    aplicacao.error(
        "Falha", new IllegalStateException("envelope", new IOException("nasc. 07/03/1941")));

    deveAcusar("nascimento", "throwable");
  }

  @Test
  void deveAcusarNomeNoStackTraceDoThrowable() {
    IllegalStateException erro = new IllegalStateException("sem dado na mensagem");
    erro.setStackTrace(
        new StackTraceElement[] {new StackTraceElement("Classe", NOME, "Arquivo.java", 1)});

    aplicacao.error("Falha", erro);

    deveAcusar("nome", "throwable");
  }

  @Test
  void deveAcusarEmailNoSuprimidoDoThrowable() {
    IllegalStateException erro = new IllegalStateException("principal");
    erro.addSuppressed(new IllegalArgumentException(EMAIL));

    aplicacao.error("Falha", erro);

    deveAcusar("email", "throwable");
  }

  @Test
  void deveAcusarNomeNoMdc() {
    MDC.put("paciente", NOME);
    aplicacao.warn("Aviso enviado");
    MDC.remove("paciente");

    deveAcusar("nome", "mdc");
  }

  @Test
  void deveAcusarTelefoneEmParDeChaveEValor() {
    aplicacao.atWarn().addKeyValue("destino", TELEFONE).log("Aviso enviado");

    deveAcusar("telefone", "chave-valor");
  }

  @Test
  void deveAcusarTipoDoExame() {
    aplicacao.warn("Exame de tomografia remarcado");

    deveAcusar("tipo do exame", "mensagem");
  }

  @Test
  void deveAcusarHorarioDoExameEmSaoPaulo() {
    aplicacao.warn("Exame das 18:37 cancelado");

    deveAcusar("horario do exame", "mensagem");
  }

  @Test
  void deveAcusarInstanteDoExame() {
    aplicacao.warn("inicio={}", INICIO_EXAME);

    deveAcusar("horario do exame", "mensagem");
  }

  @Test
  void deveAcusarDadoDePacienteNosLoggersDoSpringEDoHibernate() {
    LoggerFactory.getLogger("org.springframework.jdbc.Teste").warn("valor {}", EMAIL);
    LoggerFactory.getLogger("org.hibernate.SQL").warn("valor {}", NOME);

    assertThat(vazamentos())
        .extracting(DetectorDeDadoSensivel.Vazamento::logger)
        .contains("org.springframework.jdbc.Teste", "org.hibernate.SQL");
  }

  @Test
  void deveIgnorarSoOsLoggersDaInfraestruturaDeTeste() {
    LoggerFactory.getLogger("com.github.tomakehurst.wiremock.Teste").warn("corpo {}", TELEFONE);
    LoggerFactory.getLogger("wiremock.org.eclipse.jetty.Teste").warn("corpo {}", TELEFONE);
    LoggerFactory.getLogger("org.testcontainers.Teste").warn("saida {}", NOME);
    LoggerFactory.getLogger("tc.postgres:16").warn("saida {}", NOME);

    assertThat(vazamentos()).isEmpty();
    assertThat(DetectorDeDadoSensivel.ehInfraestruturaDeTeste("br.gov.saude.upa.X")).isFalse();
    assertThat(DetectorDeDadoSensivel.ehInfraestruturaDeTeste("org.springframework.X")).isFalse();
  }

  @Test
  void devePassarLogSoComIds() {
    MDC.put("eventoId", EVENTO.toString());
    aplicacao.warn("Aviso indeterminado: eventoId={} agendamentoId={}", EVENTO, AGENDAMENTO);
    aplicacao.error("Falha: tipo={}", IllegalStateException.class.getName());

    assertThat(captura.eventos()).hasSize(2);
    assertThat(vazamentos()).isEmpty();
    detector.exigirNenhumVazamento(captura.eventos());
  }

  @Test
  void deveReprovarComORotuloEOLoggerQuandoHaVazamento() {
    aplicacao.warn("Aviso do paciente {}", NOME);

    assertThatThrownBy(() -> detector.exigirNenhumVazamento(captura.eventos()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("nome")
        .hasMessageContaining("br.gov.saude.upa.teste.Detector");
  }

  @Test
  void deveCapturarOMdcDoMomentoDoLog() {
    MDC.put("paciente", NOME);
    aplicacao.warn("Aviso enviado");
    MDC.clear();

    // O MDC já foi limpo: o detector precisa ver o que estava no momento do log.
    deveAcusar("nome", "mdc");
  }

  // ---------- formas que o javadoc promete (rodada de revisão) ----------

  @Test
  void deveAcusarNomeNoMarcador() {
    aplicacao.warn(org.slf4j.MarkerFactory.getMarker(NOME), "Aviso enviado");

    deveAcusar("nome", "marcador");
  }

  @Test
  void deveAcusarNomeNoNomeDaThread() throws InterruptedException {
    Thread thread = new Thread(() -> aplicacao.warn("Aviso enviado"), "envio-" + NOME);
    thread.start();
    thread.join();

    deveAcusar("nome", "thread");
  }

  @Test
  void deveAcusarDataEHoraDoExameEmSaoPauloNoFormatoIso() {
    aplicacao.warn("exame em {}", "2026-10-11T18:37");

    deveAcusar("horario do exame", "mensagem");
  }

  @Test
  void deveAcusarTelefoneSemOCodigoDoPais() {
    aplicacao.warn("destino={}", "21987600123");

    deveAcusar("telefone", "mensagem");
  }

  /** A mensagem crua (o padrão) é examinada mesmo quando a formatada sai limpa. */
  @Test
  void deveAcusarNomeNaMensagemCruaNaoFormatada() {
    ch.qos.logback.classic.LoggerContext contexto =
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.spi.LoggingEvent evento =
        new ch.qos.logback.classic.spi.LoggingEvent(
            "fqcn",
            contexto.getLogger("br.gov.saude.upa.teste.Detector"),
            ch.qos.logback.classic.Level.WARN,
            "Aviso do paciente " + NOME,
            null,
            null) {
          @Override
          public String getFormattedMessage() {
            return "Aviso do paciente [removido]";
          }
        };

    assertThat(detector.vazamentos(List.of(evento)))
        .anySatisfy(
            v -> {
              assertThat(v.rotulo()).isEqualTo("nome");
              assertThat(v.onde()).isEqualTo("mensagem");
            });
  }

  @Test
  void deveAcusarTelefoneComDddEntreParentesesEHifen() {
    aplicacao.warn("destino={}", "(21) 98760-0123");

    deveAcusar("telefone", "mensagem");
  }

  @Test
  void deveAcusarTelefoneSemDddComHifen() {
    aplicacao.warn("destino={}", "98760-0123");

    deveAcusar("telefone", "mensagem");
  }

  @Test
  void deveAcusarTelefoneSeparadoPorEspacos() {
    aplicacao.warn("destino={}", "21 98760 0123");

    deveAcusar("telefone", "mensagem");
  }

  @Test
  void deveAcusarTelefoneSoComOsOitoDigitosFinais() {
    aplicacao.warn("destino={}", "8760-0123");

    deveAcusar("telefone", "mensagem");
  }

  @Test
  void deveAcusarNascimentoComHifens() {
    aplicacao.warn("nascido em {}", "07-03-1941");

    deveAcusar("nascimento", "mensagem");
  }

  @Test
  void deveAcusarNascimentoCompacto() {
    aplicacao.warn("nascido em {}", "19410307");

    deveAcusar("nascimento", "mensagem");
  }

  @Test
  void deveAcusarHorarioDoExameNoFormatoDoTimestamp() {
    // Timestamp.toString(): espaço no lugar do T, no fuso de São Paulo.
    aplicacao.warn("inicio={}", "2026-10-11 18:37:00.0");

    deveAcusar("horario do exame", "mensagem");
  }

  @Test
  void deveAcusarHorarioDoExameEmUtc() {
    aplicacao.warn("inicio={}", "2026-10-11 21:37:00.0");
    aplicacao.warn("exame das {} UTC", "21:37");

    assertThat(vazamentos())
        .filteredOn(v -> v.rotulo().equals("horario do exame"))
        .hasSizeGreaterThanOrEqualTo(1);
    assertThat(
            detector.vazamentos(
                captura.eventos().stream()
                    .filter(e -> e.getFormattedMessage().startsWith("exame das"))
                    .toList()))
        .extracting(DetectorDeDadoSensivel.Vazamento::rotulo)
        .contains("horario do exame");
  }

  @Test
  void deveAcusarSobrenomeRaroIsolado() {
    aplicacao.warn("Família {} avisada", "Quixabeira");

    deveAcusar("nome", "mensagem");
  }

  /**
   * HH:mm só conta isolado: na carga os exames cobrem quase todo minuto, e um instante legítimo
   * (sem dado do exame) não pode virar vazamento. Exame às 15:00 UTC = 12:00 em São Paulo.
   */
  @Test
  void naoDeveAcusarInstanteLegitimoQueContemOHorarioDoExameSemLimite() {
    DetectorDeDadoSensivel exameAoMeioDia =
        new DetectorDeDadoSensivel().exame("TOMOGRAFIA", Instant.parse("2026-10-11T15:00:00Z"));
    aplicacao.warn("ocorridoEm={}", "2026-10-26T15:00:00Z");
    aplicacao.warn("rodada iniciada as {}", "12:00:01");

    assertThat(exameAoMeioDia.vazamentos(captura.eventos())).isEmpty();
  }

  @Test
  void deveAcusarOHorarioDoExameIsoladoNaFrase() {
    aplicacao.warn("Exame às 18:37.");

    deveAcusar("horario do exame", "mensagem");
  }

  @Test
  void deveAcusarOHorarioDoExameIsoladoEmUtcEEmSaoPaulo() {
    DetectorDeDadoSensivel exameAoMeioDia =
        new DetectorDeDadoSensivel().exame("TOMOGRAFIA", Instant.parse("2026-10-11T15:00:00Z"));
    aplicacao.warn("exame das 15:00 (UTC)");
    aplicacao.warn("exame das 12:00");

    assertThat(exameAoMeioDia.vazamentos(captura.eventos()))
        .extracting(DetectorDeDadoSensivel.Vazamento::rotulo)
        .containsOnly("horario do exame");
    assertThat(
            exameAoMeioDia.vazamentos(
                captura.eventos().stream()
                    .filter(e -> e.getFormattedMessage().equals("exame das 12:00"))
                    .toList()))
        .isNotEmpty();
  }

  @Test
  void deveAcusarEmailCodificadoEmUrl() {
    aplicacao.warn("GET /contato?email={}", "florisbela.sintetica%40exemplo.invalid");

    deveAcusar("email", "mensagem");
  }
}
