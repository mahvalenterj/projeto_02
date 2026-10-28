package br.gov.saude.upa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.gov.saude.upa.ChavesDeTeste;
import br.gov.saude.upa.DetectorDeDadoSensivel;
import br.gov.saude.upa.application.UpaNaoEncontradaException;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CA12 na borda HTTP: nenhum log da camada web (inclusive o do 500 e o do 401) carrega dado de
 * paciente nem o token, mesmo quando a exceção interna traz esses dados na mensagem. Dados
 * sintéticos com marcadores únicos, no detector da fatia 9.
 */
class PrivacidadeDeLogDoControllerTest extends WebComSegurancaTest {

  private static final String NOME = "Marcadora Jaboticabeira Webiana";
  private static final String TELEFONE = "+5521987651234";
  private static final String EMAIL = "marcadora.web@exemplo.invalid";
  private static final LocalDate NASCIMENTO = LocalDate.of(1941, 3, 17);
  private static final Instant EXAME = Instant.parse("2026-10-27T22:43:00Z");

  private final DetectorDeDadoSensivel.Captura captura = new DetectorDeDadoSensivel.Captura();
  private final DetectorDeDadoSensivel detector =
      new DetectorDeDadoSensivel()
          .paciente(NOME, TELEFONE, EMAIL, NASCIMENTO)
          .exame("TOMOGRAFIA", EXAME);
  private final UUID upa = UUID.randomUUID();
  private final UUID equipamento = UUID.randomUUID();

  @BeforeEach
  void ligarCaptura() {
    captura.ligarNaRaiz();
  }

  @AfterEach
  void desligarCaptura() {
    captura.desligar();
    captura.limpar();
  }

  @Test
  void ca12_naoDeveRegistrarDadoDePacienteNemTokenNosLogsDoController() throws Exception {
    String mensagemComDados =
        "paciente "
            + NOME
            + " tel "
            + TELEFONE
            + " "
            + EMAIL
            + " nasc "
            + NASCIMENTO
            + " exame "
            + EXAME;
    when(marcarIndisponivel.executar(any(), any()))
        .thenReturn(UUID.randomUUID())
        .thenThrow(new IllegalStateException(mensagemComDados))
        .thenThrow(new UpaNaoEncontradaException(upa));
    String token = ChavesDeTeste.token(AGORA, upa);
    String tokenRuim = ChavesDeTeste.novo(AGORA).upas(upa).emissor(mensagemComDados).assinar();

    int sucesso = status(token);
    int erroInterno = status(token);
    int naoEncontrado = status(token);
    int foraDoToken = status(ChavesDeTeste.token(AGORA, UUID.randomUUID()));
    int naoAutenticado = status(tokenRuim);

    assertThat(List.of(sucesso, erroInterno, naoEncontrado, foraDoToken, naoAutenticado))
        .containsExactly(204, 500, 404, 404, 401);
    List<ILoggingEvent> eventos = captura.eventos();
    assertThat(eventos)
        .as("o log do 500 deveria ter passado pela captura")
        .anySatisfy(
            e ->
                assertThat(e.getFormattedMessage())
                    .contains("tipoDoErro=java.lang.IllegalStateException"));
    assertThat(eventos)
        .as("o log de auditoria do PATCH deveria ter passado pela captura")
        .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("upaId=" + upa));
    detector.exigirNenhumVazamento(eventos);
    for (ILoggingEvent evento : eventos) {
      assertThat(evento.getFormattedMessage()).doesNotContain(token).doesNotContain(tokenRuim);
      assertThat(evento.getThrowableProxy()).isNull();
    }
  }

  private int status(String token) throws Exception {
    return mvc.perform(
            patchStatus(upa, equipamento, "INDISPONIVEL")
                .header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse()
        .getStatus();
  }
}
