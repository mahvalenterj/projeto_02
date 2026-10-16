package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContatoDoPacienteTest {

  @Test
  void deveAceitarContatoComTelefoneEEmail() {
    ContatoDoPaciente contato = new ContatoDoPaciente("21999990000", "paciente@exemplo.org");

    assertThat(contato.possuiTelefone()).isTrue();
    assertThat(contato.possuiEmail()).isTrue();
    assertThat(contato.telefone()).isEqualTo("21999990000");
    assertThat(contato.email()).isEqualTo("paciente@exemplo.org");
  }

  @Test
  void deveAceitarContatoSoComTelefone() {
    ContatoDoPaciente contato = new ContatoDoPaciente("21999990000", null);

    assertThat(contato.possuiTelefone()).isTrue();
    assertThat(contato.possuiEmail()).isFalse();
  }

  @Test
  void deveAceitarContatoSoComEmail() {
    ContatoDoPaciente contato = new ContatoDoPaciente(null, "paciente@exemplo.org");

    assertThat(contato.possuiTelefone()).isFalse();
    assertThat(contato.possuiEmail()).isTrue();
  }

  @Test
  void deveAceitarContatoSemNenhumCanal() {
    ContatoDoPaciente contato = new ContatoDoPaciente(null, null);

    assertThat(contato.possuiTelefone()).isFalse();
    assertThat(contato.possuiEmail()).isFalse();
  }

  @Test
  void deveRejeitarTelefoneEmBranco() {
    assertThatThrownBy(() -> new ContatoDoPaciente("  ", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContatoDoPaciente("", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deveRejeitarEmailEmBranco() {
    assertThatThrownBy(() -> new ContatoDoPaciente(null, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContatoDoPaciente(null, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void naoDeveMostrarOsValoresNoToString() {
    ContatoDoPaciente contato = new ContatoDoPaciente("21999990000", "paciente@exemplo.org");

    assertThat(contato.toString())
        .startsWith("ContatoDoPaciente")
        .doesNotContain("21999990000")
        .doesNotContain("paciente@");
  }

  @Test
  void naoDeveVazarValoresNaMensagemDeErro() {
    assertThatThrownBy(() -> new ContatoDoPaciente(" ", "paciente@exemplo.org"))
        .hasMessageNotContaining("paciente@");
  }
}
