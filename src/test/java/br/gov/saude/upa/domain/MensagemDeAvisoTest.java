package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MensagemDeAvisoTest {

  private static final UUID ID_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID OUTRA_UPA = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
  private static final UUID ID_TOMOGRAFO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID ID_RAIO_X = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID ID_PACIENTE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final Instant CRIADO_EM = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant EXAME_15H30 = Instant.parse("2026-10-11T18:30:00Z");

  private static final EnderecoAlternativo ENDERECO_TOMOGRAFO =
      new EnderecoAlternativo("UPA Cascadura, Rua Carolina Machado, 100");
  private static final EnderecoAlternativo ENDERECO_RAIO_X =
      new EnderecoAlternativo("UPA Realengo, Av. Santa Cruz, 200");

  private static UPA upaCom(Map<TipoEquipamento, EnderecoAlternativo> enderecos) {
    return new UPA(
        ID_UPA,
        "Madureira",
        List.of(
            new Equipamento(
                ID_TOMOGRAFO, TipoEquipamento.TOMOGRAFO, StatusEquipamento.INDISPONIVEL),
            new Equipamento(ID_RAIO_X, TipoEquipamento.RAIO_X, StatusEquipamento.INDISPONIVEL)),
        enderecos);
  }

  private static UPA upaComAmbosOsEnderecos() {
    return upaCom(
        Map.of(
            TipoEquipamento.TOMOGRAFO, ENDERECO_TOMOGRAFO,
            TipoEquipamento.RAIO_X, ENDERECO_RAIO_X));
  }

  private static AgendamentoExame agendamento(UUID idUpa, UUID idEquipamento, Instant inicio) {
    return new AgendamentoTomografia(
        UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        ID_PACIENTE,
        idUpa,
        idEquipamento,
        CRIADO_EM,
        inicio);
  }

  @Test
  void deveIncluirEnderecoAlternativoNaMensagem() {
    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(
            upaComAmbosOsEnderecos(), agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30));

    assertThat(mensagem.texto())
        .isEqualTo(
            "UPA Madureira: o tomógrafo está indisponível hoje e o seu exame das 15:30 não poderá"
                + " ser feito aqui. Procure: UPA Cascadura, Rua Carolina Machado, 100.");
  }

  @Test
  void deveUsarOEnderecoDoTipoDoEquipamento() {
    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(
            upaComAmbosOsEnderecos(), agendamento(ID_UPA, ID_RAIO_X, EXAME_15H30));

    assertThat(mensagem.texto())
        .isEqualTo(
            "UPA Madureira: o aparelho de raio-X está indisponível hoje e o seu exame das 15:30"
                + " não poderá ser feito aqui. Procure: UPA Realengo, Av. Santa Cruz, 200.");
  }

  @TestFactory
  Stream<DynamicTest> deveNomearOEquipamentoPeloTipo() {
    Map<TipoEquipamento, String> esperado =
        Map.of(
            TipoEquipamento.TOMOGRAFO, "o tomógrafo está",
            TipoEquipamento.RAIO_X, "o aparelho de raio-X está");
    Map<TipoEquipamento, UUID> idPorTipo =
        Map.of(TipoEquipamento.TOMOGRAFO, ID_TOMOGRAFO, TipoEquipamento.RAIO_X, ID_RAIO_X);

    return Stream.of(TipoEquipamento.values())
        .map(
            tipo ->
                DynamicTest.dynamicTest(
                    tipo.name(),
                    () ->
                        assertThat(
                                MensagemDeAviso.compor(
                                        upaComAmbosOsEnderecos(),
                                        agendamento(ID_UPA, idPorTipo.get(tipo), EXAME_15H30))
                                    .texto())
                            .contains(esperado.get(tipo))));
  }

  @Test
  void deveFormatarOHorarioEmSaoPauloComZeroAEsquerda() {
    UPA upa = upaComAmbosOsEnderecos();

    assertThat(
            MensagemDeAviso.compor(
                    upa, agendamento(ID_UPA, ID_TOMOGRAFO, Instant.parse("2026-10-11T12:05:00Z")))
                .texto())
        .contains("exame das 09:05 ");
    assertThat(
            MensagemDeAviso.compor(
                    upa, agendamento(ID_UPA, ID_TOMOGRAFO, Instant.parse("2026-10-12T02:59:00Z")))
                .texto())
        .contains("exame das 23:59 ");
  }

  @Test
  void deveEnviarSemEnderecoQuandoAUpaNaoTemAlternativoParaOTipo() {
    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(upaCom(Map.of()), agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30));

    assertThat(mensagem.texto())
        .isEqualTo(
            "UPA Madureira: o tomógrafo está indisponível hoje e o seu exame das 15:30 não poderá"
                + " ser feito aqui. Procure a recepção da UPA para orientação.");
  }

  @Test
  void naoDeveUsarOEnderecoDeOutroTipoDeEquipamento() {
    UPA upa = upaCom(Map.of(TipoEquipamento.RAIO_X, ENDERECO_RAIO_X));

    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(upa, agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30));

    assertThat(mensagem.texto())
        .endsWith("Procure a recepção da UPA para orientação.")
        .doesNotContain(ENDERECO_RAIO_X.texto());
  }

  @Test
  void naoDeveConterDadosDoPacienteNaMensagem() {
    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(
            upaComAmbosOsEnderecos(), agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30));

    assertThat(mensagem.texto()).doesNotContain(ID_PACIENTE.toString());
  }

  @Test
  void deveRejeitarAgendamentoDeOutraUpa() {
    UPA upa = upaComAmbosOsEnderecos();
    AgendamentoExame deOutraUpa = agendamento(OUTRA_UPA, ID_TOMOGRAFO, EXAME_15H30);

    assertThatThrownBy(() -> MensagemDeAviso.compor(upa, deOutraUpa))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OUTRA_UPA.toString())
        .hasMessageContaining(ID_UPA.toString());
  }

  @Test
  void deveLancarNaoEncontradoQuandoOEquipamentoNaoPertencerAUpa() {
    UPA upa = upaComAmbosOsEnderecos();
    UUID desconhecido = UUID.fromString("00000000-0000-0000-0000-0000000000b9");
    AgendamentoExame agendamento = agendamento(ID_UPA, desconhecido, EXAME_15H30);

    assertThatThrownBy(() -> MensagemDeAviso.compor(upa, agendamento))
        .isInstanceOf(EquipamentoNaoEncontradoException.class)
        .hasMessageContaining(desconhecido.toString());
  }

  @Test
  void deveRejeitarArgumentosNulos() {
    UPA upa = upaComAmbosOsEnderecos();
    AgendamentoExame agendamento = agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30);

    assertThatThrownBy(() -> MensagemDeAviso.compor(null, agendamento))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MensagemDeAviso.compor(upa, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void deveRejeitarTextoNuloOuEmBranco() {
    assertThatThrownBy(() -> new MensagemDeAviso(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MensagemDeAviso("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MensagemDeAviso("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringNaoDeveMostrarOTexto() {
    MensagemDeAviso mensagem =
        MensagemDeAviso.compor(
            upaComAmbosOsEnderecos(), agendamento(ID_UPA, ID_TOMOGRAFO, EXAME_15H30));

    assertThat(mensagem.toString())
        .startsWith("MensagemDeAviso")
        .doesNotContain("15:30")
        .doesNotContain("Madureira")
        .doesNotContain("Cascadura");
  }
}
