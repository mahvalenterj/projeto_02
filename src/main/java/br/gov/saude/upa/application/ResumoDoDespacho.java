package br.gov.saude.upa.application;

/** Contagens de uma rodada do despachante. */
public record ResumoDoDespacho(int reivindicados, int processados, int devolvidos, int esgotados) {}
