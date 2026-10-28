package br.gov.saude.upa.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 e 403 da cadeia de segurança em ProblemDetail. Toda recusa de autenticação (sem token,
 * esquema que não é Bearer, assinatura, emissor, audiência, validade) tem o MESMO corpo e o MESMO
 * {@code WWW-Authenticate: Bearer realm="upa"}: o padrão do Spring diria o motivo em {@code
 * error_description}, o que ajudaria quem tenta forjar token. Nada da exceção vai para a resposta
 * nem para o log (o token nunca é registrado).
 */
@Component
public class RespostaDeSegurancaEmProblemDetail
    implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

  static final String DESAFIO = "Bearer realm=\"upa\"";

  private final ObjectMapper json;

  public RespostaDeSegurancaEmProblemDetail(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, DESAFIO);
    escrever(response, ProblemasHttp.naoAutenticado(request));
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    escrever(response, ProblemasHttp.acessoNegado(request));
  }

  /**
   * URL rejeitada pelo firewall do Spring Security ({@code ;}, {@code //}, codificação suspeita):
   * 400 genérico SEM {@code instance}, para não ecoar a URL rejeitada. Sem isto, o padrão faz
   * {@code sendError(400)} e o container responde pelo {@code /error}.
   */
  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, RequestRejectedException e)
      throws IOException {
    escrever(response, ProblemasHttp.requisicaoRejeitada());
  }

  private void escrever(HttpServletResponse response, ProblemDetail problema) throws IOException {
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("type", problema.getType().toString());
    corpo.put("title", problema.getTitle());
    corpo.put("status", problema.getStatus());
    corpo.put("detail", problema.getDetail());
    if (problema.getInstance() != null) {
      corpo.put("instance", problema.getInstance().toString());
    }
    response.setStatus(problema.getStatus());
    response.setContentType("application/problem+json");
    json.writeValue(response.getOutputStream(), corpo);
  }
}
