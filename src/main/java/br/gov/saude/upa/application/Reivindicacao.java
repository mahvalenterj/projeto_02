package br.gov.saude.upa.application;

import java.util.List;

/** Resultado de uma reivindicação: eventos legíveis para processar e linhas já esgotadas. */
public record Reivindicacao(List<EventoReivindicado> eventos, List<EventoIlegivel> ilegiveis) {

  public Reivindicacao {
    eventos = List.copyOf(eventos);
    ilegiveis = List.copyOf(ilegiveis);
  }

  public static Reivindicacao vazia() {
    return new Reivindicacao(List.of(), List.of());
  }

  public boolean estaVazia() {
    return eventos.isEmpty() && ilegiveis.isEmpty();
  }
}
