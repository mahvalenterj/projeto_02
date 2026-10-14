package br.gov.saude.upa.domain;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Texto enviado ao paciente. Não leva o nome do paciente; leva o horário do exame, por isso o
 * {@code toString} não mostra o texto.
 */
public record MensagemDeAviso(String texto) {

  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
  private static final String ORIENTACAO_SEM_ENDERECO =
      "Procure a recepção da UPA para orientação.";

  public MensagemDeAviso {
    Objects.requireNonNull(texto, "texto");
    if (texto.isBlank()) {
      throw new IllegalArgumentException("texto da mensagem não pode ser vazio");
    }
  }

  /**
   * Monta o aviso do agendamento. Se a UPA não tem endereço alternativo para o tipo do equipamento,
   * a mensagem sai mesmo assim, com a orientação fixa.
   */
  public static MensagemDeAviso compor(UPA upa, AgendamentoExame agendamento) {
    Objects.requireNonNull(upa, "upa");
    Objects.requireNonNull(agendamento, "agendamento");
    if (!agendamento.idUpa().equals(upa.id())) {
      throw new IllegalArgumentException(
          "Agendamento "
              + agendamento.id()
              + " é da UPA "
              + agendamento.idUpa()
              + " e não da UPA "
              + upa.id());
    }
    TipoEquipamento tipo = upa.tipoDoEquipamento(agendamento.idEquipamento());
    String hora = HORA.format(agendamento.inicioExame().atZone(Fuso.SAO_PAULO));
    String orientacao =
        upa.enderecoAlternativoPara(tipo)
            .map(endereco -> "Procure: " + endereco.texto() + ".")
            .orElse(ORIENTACAO_SEM_ENDERECO);
    return new MensagemDeAviso(
        "UPA "
            + upa.nome()
            + ": o "
            + nomeDoEquipamento(tipo)
            + " está indisponível hoje e o seu exame das "
            + hora
            + " não poderá ser feito aqui. "
            + orientacao);
  }

  /** Sem {@code default}: novo tipo de equipamento quebra a compilação. */
  private static String nomeDoEquipamento(TipoEquipamento tipo) {
    return switch (tipo) {
      case TOMOGRAFO -> "tomógrafo";
      case RAIO_X -> "aparelho de raio-X";
    };
  }

  /** Sem o texto: ele contém o horário do exame, que é dado sensível. */
  @Override
  public String toString() {
    return "MensagemDeAviso[...]";
  }
}
