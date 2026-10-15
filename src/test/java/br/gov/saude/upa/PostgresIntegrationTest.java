package br.gov.saude.upa;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base dos testes de integração: um único contexto Spring e um único container Postgres,
 * compartilhados por todas as classes que a estendem (a máquina é lenta).
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
public abstract class PostgresIntegrationTest {}
