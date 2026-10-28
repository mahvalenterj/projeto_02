package br.gov.saude.upa;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base dos testes de integração: um único contexto Spring e um único container Postgres,
 * compartilhados por todas as classes que a estendem (a máquina é lenta). A chave pública do JWT é
 * a do par gerado na execução ({@link ChavesDeTeste}), nunca a de dev.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@TestPropertySource(
    properties = {
      "upa.canais.sms.base-url=http://localhost:1",
      "upa.canais.sms.token=token-falso-de-teste",
      "upa.canais.email.base-url=http://localhost:1",
      "upa.canais.email.token=token-falso-de-teste",
      // O agendador ligado consumiria as linhas do outbox que os testes usam.
      "upa.outbox.habilitado=false",
      "upa.seguranca.jwt.emissor=" + ChavesDeTeste.EMISSOR,
      "upa.seguranca.jwt.audiencia=" + ChavesDeTeste.AUDIENCIA
    })
public abstract class PostgresIntegrationTest {

  @DynamicPropertySource
  static void chavePublicaDoJwt(DynamicPropertyRegistry registro) {
    registro.add("upa.seguranca.jwt.chave-publica", ChavesDeTeste::pemPublica);
  }
}
