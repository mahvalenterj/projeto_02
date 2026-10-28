package br.gov.saude.upa.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Os corpos de erro da API (RFC 9457). Título e detalhe são FIXOS por tipo de erro: nunca carregam
 * mensagem de exceção, id, SQL ou stack trace. O único campo que varia é {@code instance} (o
 * caminho pedido), por isso o 404 de "não existe" e o de "não é seu" são idênticos (CA13).
 */
final class ProblemasHttp {

  private ProblemasHttp() {}

  static ProblemDetail naoEncontrado(HttpServletRequest request) {
    return problema(
        HttpStatus.NOT_FOUND,
        "Recurso não encontrado",
        "O recurso pedido não existe ou não está disponível para este operador.",
        request);
  }

  static ProblemDetail transicaoInvalida(HttpServletRequest request) {
    return problema(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Transição de status inválida",
        "O status pedido não é aceito a partir do status atual do equipamento.",
        request);
  }

  static ProblemDetail conflito(HttpServletRequest request) {
    return problema(
        HttpStatus.CONFLICT,
        "Conflito de alteração",
        "O equipamento foi alterado por outra operação. Consulte de novo e repita o pedido.",
        request);
  }

  static ProblemDetail corpoInvalido(HttpServletRequest request) {
    return problema(
        HttpStatus.BAD_REQUEST,
        "Requisição inválida",
        "O corpo da requisição está malformado ou incompleto.",
        request);
  }

  static ProblemDetail erroInterno(HttpServletRequest request) {
    return problema(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Erro interno",
        "Erro inesperado ao processar o pedido. Tente de novo mais tarde.",
        request);
  }

  static ProblemDetail naoAutenticado(HttpServletRequest request) {
    return problema(
        HttpStatus.UNAUTHORIZED, "Não autenticado", "Credencial ausente ou inválida.", request);
  }

  static ProblemDetail acessoNegado(HttpServletRequest request) {
    return problema(HttpStatus.FORBIDDEN, "Acesso negado", "Operação não permitida.", request);
  }

  static ProblemDetail metodoNaoPermitido(HttpServletRequest request) {
    return problema(
        HttpStatus.METHOD_NOT_ALLOWED,
        "Método não permitido",
        "O método HTTP usado não é aceito neste recurso.",
        request);
  }

  static ProblemDetail tipoNaoSuportado(HttpServletRequest request) {
    return problema(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "Tipo de conteúdo não suportado",
        "O tipo de conteúdo enviado não é aceito neste recurso.",
        request);
  }

  static ProblemDetail formatoNaoAceitavel(HttpServletRequest request) {
    return problema(
        HttpStatus.NOT_ACCEPTABLE,
        "Formato não aceitável",
        "Nenhum formato de resposta disponível atende ao cabeçalho Accept.",
        request);
  }

  /** Firewall: sem {@code instance}, para não ecoar a URL rejeitada. */
  static ProblemDetail requisicaoRejeitada() {
    return problema(
        HttpStatus.BAD_REQUEST, "Requisição inválida", "A requisição foi rejeitada.", null);
  }

  /**
   * Qualquer outro status (exceções do Spring MVC e o {@code /error} do container): título e
   * detalhe fixos por faixa, nunca a mensagem original.
   */
  static ProblemDetail porStatus(int status, HttpServletRequest request) {
    HttpStatus conhecido = HttpStatus.resolve(status);
    if (conhecido == null || conhecido.is5xxServerError() || !conhecido.isError()) {
      return erroInterno(request);
    }
    return switch (conhecido) {
      case NOT_FOUND -> naoEncontrado(request);
      case METHOD_NOT_ALLOWED -> metodoNaoPermitido(request);
      case UNSUPPORTED_MEDIA_TYPE -> tipoNaoSuportado(request);
      case NOT_ACCEPTABLE -> formatoNaoAceitavel(request);
      case UNAUTHORIZED -> naoAutenticado(request);
      case FORBIDDEN -> acessoNegado(request);
      case BAD_REQUEST ->
          problema(
              HttpStatus.BAD_REQUEST, "Requisição inválida", "A requisição é inválida.", request);
      default ->
          problema(
              conhecido, "Requisição não atendida", "A requisição não pôde ser atendida.", request);
    };
  }

  static ProblemDetail problema(
      HttpStatus status, String titulo, String detalhe, HttpServletRequest request) {
    ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
    problema.setTitle(titulo);
    if (request == null) {
      return problema;
    }
    try {
      problema.setInstance(URI.create(request.getRequestURI()));
    } catch (IllegalArgumentException e) {
      // Caminho que não forma URI: o problema sai sem instance, nunca com erro.
      problema.setInstance(null);
    }
    return problema;
  }
}
