package br.gov.saude.upa.web;

import br.gov.saude.upa.application.ConflitoDeConcorrenciaException;
import br.gov.saude.upa.application.EquipamentoInexistenteException;
import br.gov.saude.upa.application.UpaNaoEncontradaException;
import br.gov.saude.upa.domain.EquipamentoNaoEncontradoException;
import br.gov.saude.upa.domain.TransicaoDeStatusInvalidaException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduz exceções em ProblemDetail (RFC 9457) com corpo fixo por tipo (CA13): 404 idêntico para UPA
 * inexistente, equipamento inexistente ou de outra UPA, UPA fora do token e id malformado no
 * caminho; 422 transição inválida; 409 conflito de versão; 400 corpo malformado; 500 genérico. As
 * exceções do Spring MVC (405, 415 etc.) seguem o ProblemDetail padrão do {@link
 * ResponseEntityExceptionHandler}. Nada de mensagem interna no corpo nem no log: o log do 500 leva
 * só o nome da classe do erro (a mensagem poderia ter dado de paciente).
 */
@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);

  @ExceptionHandler({
    UpaForaDoAlcanceException.class,
    UpaNaoEncontradaException.class,
    EquipamentoNaoEncontradoException.class,
    EquipamentoInexistenteException.class
  })
  ResponseEntity<ProblemDetail> naoEncontrado(HttpServletRequest request) {
    return responder(ProblemasHttp.naoEncontrado(request));
  }

  @ExceptionHandler({
    TransicaoDeStatusInvalidaException.class,
    StatusSolicitadoInvalidoException.class
  })
  ResponseEntity<ProblemDetail> transicaoInvalida(HttpServletRequest request) {
    return responder(ProblemasHttp.transicaoInvalida(request));
  }

  @ExceptionHandler(ConflitoDeConcorrenciaException.class)
  ResponseEntity<ProblemDetail> conflito(
      ConflitoDeConcorrenciaException e, HttpServletRequest request) {
    LOG.warn("Conflito de versão ao alterar status: upaId={}", e.idUpa());
    return responder(ProblemasHttp.conflito(request));
  }

  @ExceptionHandler(CorpoInvalidoException.class)
  ResponseEntity<ProblemDetail> corpoInvalido(HttpServletRequest request) {
    return responder(ProblemasHttp.corpoInvalido(request));
  }

  /** Último recurso: nunca engole o erro (vira log de erro), mas também não o expõe. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> erroInesperado(Exception e, HttpServletRequest request) {
    LOG.error(
        "Erro inesperado na requisição: tipoDoErro={}, metodo={}, caminho={}",
        e.getClass().getName(),
        request.getMethod(),
        request.getRequestURI());
    return responder(ProblemasHttp.erroInterno(request));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return generico(ProblemasHttp.corpoInvalido(servlet(request)));
  }

  /** Id do caminho que não é UUID não identifica recurso nenhum: o mesmo 404 (CA13). */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (e instanceof MethodArgumentTypeMismatchException argumento
        && argumento
            .getParameter()
            .hasParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class)) {
      return generico(ProblemasHttp.naoEncontrado(servlet(request)));
    }
    return generico(ProblemasHttp.corpoInvalido(servlet(request)));
  }

  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(
      NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return generico(ProblemasHttp.naoEncontrado(servlet(request)));
  }

  /**
   * Todas as demais exceções do Spring MVC (405, 406, 415, parâmetro ausente etc.) passam por aqui:
   * o corpo é trocado pelo ProblemDetail FIXO do status, sem ecoar método, tipo de conteúdo ou nome
   * de parâmetro enviados. Os cabeçalhos do Spring ({@code Allow}, {@code Accept}) ficam.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (request instanceof org.springframework.web.context.request.ServletWebRequest servlet
        && servlet.getResponse() != null
        && servlet.getResponse().isCommitted()) {
      return null;
    }
    ProblemDetail problema = ProblemasHttp.porStatus(status.value(), servlet(request));
    return ResponseEntity.status(problema.getStatus()).headers(headers).body(problema);
  }

  private static HttpServletRequest servlet(WebRequest request) {
    return ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
  }

  private static ResponseEntity<ProblemDetail> responder(ProblemDetail problema) {
    return ResponseEntity.status(problema.getStatus()).body(problema);
  }

  private static ResponseEntity<Object> generico(ProblemDetail problema) {
    return ResponseEntity.status(problema.getStatus()).body(problema);
  }
}
