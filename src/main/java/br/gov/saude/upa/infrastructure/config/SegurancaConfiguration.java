package br.gov.saude.upa.infrastructure.config;

import br.gov.saude.upa.web.RespostaDeSegurancaEmProblemDetail;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server JWT RS256 (ADR 0004). A aplicação só tem a chave PÚBLICA: valida, não emite.
 * Stateless, sem CSRF (API sem cookie de sessão), sem login por formulário nem Basic. {@code
 * /api/**} exige token; {@code /actuator/health} é aberto; o resto é negado, inclusive {@code
 * /dev/**} e o Swagger: eles só passam pela cadeia própria do perfil dev ({@code
 * DesenvolvimentoConfiguration}, ordem anterior a esta). URL rejeitada pelo firewall vira 400 em
 * ProblemDetail, sem ecoar a URL. Na subida, a chave pública de DESENVOLVIMENTO é recusada fora do
 * perfil dev ({@link TravaDaChaveDeDesenvolvimento}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SegurancaJwtProperties.class)
public class SegurancaConfiguration {

  static final Duration TOLERANCIA_DE_RELOGIO = Duration.ofSeconds(30);
  static final Duration DURACAO_MAXIMA_DO_TOKEN = Duration.ofMinutes(15);

  @Bean
  SecurityFilterChain cadeiaDeSeguranca(
      HttpSecurity http, RespostaDeSegurancaEmProblemDetail resposta, JwtDecoder decoder)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/**")
                    .authenticated()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o ->
                o.authenticationEntryPoint(resposta)
                    .accessDeniedHandler(resposta)
                    .jwt(j -> j.decoder(decoder)))
        .exceptionHandling(e -> e.authenticationEntryPoint(resposta).accessDeniedHandler(resposta));
    return http.build();
  }

  /**
   * Só RS256, com a chave pública configurada; valida {@code iss}, {@code aud}, {@code sub}, {@code
   * exp}/{@code nbf} com 30 s de tolerância e a duração máxima de 15 min. O relógio é o da
   * aplicação.
   */
  @Bean
  JwtDecoder jwtDecoder(
      SegurancaJwtProperties p, ResourceLoader recursos, Clock clock, Environment ambiente) {
    RSAPublicKey chave = ChavesPem.chavePublica(p.chavePublica(), recursos);
    TravaDaChaveDeDesenvolvimento.verificar(chave, ambiente.acceptsProfiles(Profiles.of("dev")));
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(chave).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    JwtTimestampValidator validade = new JwtTimestampValidator(TOLERANCIA_DE_RELOGIO);
    validade.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            validade,
            new DuracaoMaximaDoToken(DURACAO_MAXIMA_DO_TOKEN, TOLERANCIA_DE_RELOGIO, clock),
            new JwtIssuerValidator(p.emissor()),
            new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(p.audiencia())),
            new JwtClaimValidator<String>(
                JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
    return decoder;
  }
}
