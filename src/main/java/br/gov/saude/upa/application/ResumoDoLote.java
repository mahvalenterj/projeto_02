package br.gov.saude.upa.application;

/** Contagens do lote. Sem dado de paciente. */
public record ResumoDoLote(
    int selecionados,
    int confirmados,
    int cancelados,
    int emRevisaoManual,
    int indeterminados,
    int pulados,
    int falhasInesperadas) {

  public ResumoDoLote {
    exigirNaoNegativo(selecionados, "selecionados");
    exigirNaoNegativo(confirmados, "confirmados");
    exigirNaoNegativo(cancelados, "cancelados");
    exigirNaoNegativo(emRevisaoManual, "emRevisaoManual");
    exigirNaoNegativo(indeterminados, "indeterminados");
    exigirNaoNegativo(pulados, "pulados");
    exigirNaoNegativo(falhasInesperadas, "falhasInesperadas");
  }

  private static void exigirNaoNegativo(int valor, String campo) {
    if (valor < 0) {
      throw new IllegalArgumentException(campo + " não pode ser negativo");
    }
  }
}
