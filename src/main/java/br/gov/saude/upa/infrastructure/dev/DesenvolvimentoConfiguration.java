package br.gov.saude.upa.infrastructure.dev;

import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.infrastructure.config.ChavesPem;
import br.gov.saude.upa.infrastructure.config.SegurancaJwtProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.validation.annotation.Validated;

/**
 * SOMENTE DESENVOLVIMENTO: liga o cenário sintético e o emissor de token de dev. A chave privada de
 * dev ({@code upa.dev.jwt.chave-privada}) só é lida neste perfil.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@EnableConfigurationProperties(DesenvolvimentoConfiguration.JwtDeDesenvolvimento.class)
class DesenvolvimentoConfiguration {

  @Validated
  @ConfigurationProperties("upa.dev.jwt")
  record JwtDeDesenvolvimento(@NotBlank String chavePrivada) {

    @Override
    public String toString() {
      return "JwtDeDesenvolvimento[chavePrivada=***]";
    }
  }

  @Bean
  CenarioDeDesenvolvimento cenarioDeDesenvolvimento(
      UPAPersistencia upas, JdbcTemplate jdbc, Clock clock) {
    return new CenarioDeDesenvolvimento(upas, jdbc, clock);
  }

  @Bean
  EmissorDeTokenDeDesenvolvimento emissorDeTokenDeDesenvolvimento(
      JwtDeDesenvolvimento dev,
      SegurancaJwtProperties seguranca,
      ResourceLoader recursos,
      Clock clock) {
    return new EmissorDeTokenDeDesenvolvimento(
        ChavesPem.chavePublica(seguranca.chavePublica(), recursos),
        ChavesPem.chavePrivada(dev.chavePrivada(), recursos),
        seguranca.emissor(),
        seguranca.audiencia(),
        clock);
  }

  /**
   * Cadeia de segurança SÓ das rotas de dev e do Swagger, antes da principal. Fora do perfil dev
   * ela não existe e essas rotas caem no {@code denyAll} da cadeia principal. As rotas {@code
   * /dev/**} ainda exigem loopback no controller.
   */
  @Bean
  @Order(1)
  SecurityFilterChain cadeiaDeSegurancaDeDesenvolvimento(HttpSecurity http) throws Exception {
    http.securityMatcher(
            "/dev/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  /** Esquema bearer (JWT) no OpenAPI: o botão Authorize do Swagger envia o token do /dev/token. */
  @Bean
  OpenAPI openApiDeDesenvolvimento() {
    return new OpenAPI()
        .components(
            new Components()
                .addSecuritySchemes(
                    ESQUEMA_BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_BEARER));
  }

  @Bean
  AvisoDePerfilDev avisoDePerfilDev() {
    return new AvisoDePerfilDev();
  }

  static final String ESQUEMA_BEARER = "bearer-jwt";
}
