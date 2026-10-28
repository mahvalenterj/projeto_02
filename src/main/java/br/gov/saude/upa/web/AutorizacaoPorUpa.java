package br.gov.saude.upa.web;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Regra "o operador pode operar esta UPA" (CA13, ADR 0004): a UPA precisa estar no claim {@code
 * upas} do token. Roda ANTES de qualquer consulta: a resposta não depende de a UPA existir. Claim
 * ausente, fora do formato de lista ou com itens que não são UUID não dá acesso a nada (falha
 * fechada).
 */
@Component
public class AutorizacaoPorUpa {

  static final String CLAIM_UPAS = "upas";

  /**
   * @throws UpaForaDoAlcanceException se o token não dá acesso à UPA
   */
  public void exigirQuePodeOperar(Jwt jwt, UUID upaId) {
    if (!podeOperar(jwt, upaId)) {
      throw new UpaForaDoAlcanceException(upaId);
    }
  }

  public boolean podeOperar(Jwt jwt, UUID upaId) {
    if (jwt == null || upaId == null) {
      return false;
    }
    if (!(jwt.getClaims().get(CLAIM_UPAS) instanceof Collection<?> upas)) {
      return false;
    }
    for (Object item : upas) {
      if (item instanceof String texto && corresponde(texto, upaId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean corresponde(String texto, UUID upaId) {
    try {
      return UUID.fromString(texto.strip()).equals(upaId);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
