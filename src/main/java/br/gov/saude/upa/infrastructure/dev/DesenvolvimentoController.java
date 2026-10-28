package br.gov.saude.upa.infrastructure.dev;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * SOMENTE DESENVOLVIMENTO ({@code @Profile("dev")}): fora do perfil dev estas rotas não existem.
 * Mesmo no dev, só atendem requisição vinda do loopback (127.0.0.0/8 ou ::1); de outro endereço
 * respondem o 404 genérico, como se não existissem. {@code POST /dev/cenario} cria dados
 * sintéticos; {@code POST /dev/token} emite um token para até {@value #MAXIMO_DE_UPAS} UPAs. O
 * token emitido não vai para o log.
 */
@RestController
@RequestMapping("/dev")
@Profile("dev")
class DesenvolvimentoController {

  static final int MAXIMO_DE_UPAS = 20;

  private final CenarioDeDesenvolvimento cenario;
  private final EmissorDeTokenDeDesenvolvimento emissor;

  DesenvolvimentoController(
      CenarioDeDesenvolvimento cenario, EmissorDeTokenDeDesenvolvimento emissor) {
    this.cenario = Objects.requireNonNull(cenario, "cenario");
    this.emissor = Objects.requireNonNull(emissor, "emissor");
  }

  @PostMapping("/cenario")
  CenarioDeDesenvolvimento.CenarioCriado criarCenario(HttpServletRequest request)
      throws NoResourceFoundException {
    exigirLoopback(request);
    return cenario.criar();
  }

  @PostMapping("/token")
  Map<String, String> emitirToken(@RequestBody PedidoDeToken pedido, HttpServletRequest request)
      throws NoResourceFoundException {
    exigirLoopback(request);
    if (pedido.upas() == null
        || pedido.upas().isEmpty()
        || pedido.upas().size() > MAXIMO_DE_UPAS
        || pedido.upas().stream().anyMatch(Objects::isNull)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    return Map.of("token", emissor.emitir(pedido.upas()));
  }

  /** {@code remoteAddr} é o par TCP (sem cabeçalho de proxy configurado), sempre um IP literal. */
  static void exigirLoopback(HttpServletRequest request) throws NoResourceFoundException {
    if (!ehLoopback(request.getRemoteAddr())) {
      throw new NoResourceFoundException(HttpMethod.POST, request.getRequestURI());
    }
  }

  static boolean ehLoopback(String endereco) {
    if (endereco == null || endereco.isBlank() || !endereco.matches("[0-9a-fA-F:.%]+")) {
      return false;
    }
    try {
      return InetAddress.getByName(endereco).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  record PedidoDeToken(List<UUID> upas) {}
}
