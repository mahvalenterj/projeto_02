package br.gov.saude.upa.web;

/**
 * Corpo do PATCH de status. {@code status} chega como texto: um valor fora de {@code INDISPONIVEL}
 * e {@code DISPONIVEL} é transição inválida (422), não corpo malformado (400).
 */
public record AlteracaoDeStatusRequest(String status) {}
