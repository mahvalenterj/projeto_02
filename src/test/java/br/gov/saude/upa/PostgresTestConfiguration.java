package br.gov.saude.upa;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres 16 real (o mesmo da produção) para os testes de integração; nunca H2. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
  }
}
