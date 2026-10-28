package br.gov.saude.upa.web;

/**
 * Status pedido fora de {@code INDISPONIVEL} e {@code DISPONIVEL}: vira 422, o mesmo da transição
 * inválida do domínio. A mensagem não repete o valor recebido.
 */
class StatusSolicitadoInvalidoException extends RuntimeException {

  StatusSolicitadoInvalidoException() {
    super("Status solicitado não é aceito pela API");
  }
}
