package br.gov.saude.upa.application;

import java.util.UUID;

/**
 * Linha do outbox cujo payload não pôde ser lido. A fila já a marcou como esgotada; o despachante
 * só alerta. Guarda apenas o id e a contagem.
 */
public record EventoIlegivel(UUID eventoId, int tentativas) {}
