package br.gov.saude.upa.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Substitui o {@code BasicErrorController} do Boot: o que chega ao {@code /error} (erro fora do
 * DispatcherServlet, {@code sendError} de filtro ou do container) sai em ProblemDetail genérico,
 * nunca com {@code timestamp}/{@code error}/{@code path}, mensagem ou stack trace. O {@code
 * instance} é o caminho original, exceto no 400 (pode ser a própria URL rejeitada).
 */
@RestController
public class ErroGenericoController implements ErrorController {

  private static final Logger LOG = LoggerFactory.getLogger(ErroGenericoController.class);

  @RequestMapping("${server.error.path:${error.path:/error}}")
  ResponseEntity<ProblemDetail> erro(HttpServletRequest request) {
    int status =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer codigo
            ? codigo
            : 500;
    ProblemDetail problema = ProblemasHttp.porStatus(status, null);
    if (problema.getStatus() >= 500
        && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable erro) {
      LOG.error("Erro fora do controller: tipoDoErro={}", erro.getClass().getName());
    }
    if (problema.getStatus() != 400
        && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String caminho) {
      try {
        problema.setInstance(URI.create(caminho));
      } catch (IllegalArgumentException e) {
        problema.setInstance(null);
      }
    }
    return ResponseEntity.status(problema.getStatus()).body(problema);
  }
}
