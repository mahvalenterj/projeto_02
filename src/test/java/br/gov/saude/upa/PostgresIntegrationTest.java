package br.gov.saude.upa;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base dos testes de integração: um único contexto Spring e um único container Postgres,
 * compartilhados por todas as classes que a estendem (a máquina é lenta).
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
      "upa.outbox.habilitado=false"
    })
public abstract class PostgresIntegrationTest {}
