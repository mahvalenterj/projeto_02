package br.gov.saude.upa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Fora do perfil {@code dev} (o contexto padrão dos testes de integração, sem contexto novo): os
 * endpoints de dev e o Swagger não têm cadeia própria e caem no denyAll (401), o {@code health} é
 * aberto e sem detalhes e o resto exige autenticação.
 */
class RotasForaDoPerfilDevTest extends PostgresIntegrationTest {

  @Autowired private WebApplicationContext contexto;
  private MockMvc mvc;

  @BeforeEach
  void montarMockMvc() {
    mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
  }

  private MockHttpServletResponse executar(MockHttpServletRequestBuilder requisicao)
      throws Exception {
    return mvc.perform(requisicao).andReturn().getResponse();
  }

  @Test
  void naoDeveExistirEndpointDeDevForaDoPerfilDev() throws Exception {
    MockHttpServletResponse cenario =
        executar(post("/dev/cenario").contentType(MediaType.APPLICATION_JSON).content("{}"));
    MockHttpServletResponse token =
        executar(
            post("/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"upas\":[\"00000000-0000-0000-0000-000000000001\"]}"));

    // Fora do dev, a única cadeia de segurança nega tudo que não é /api nem health: 401.
    assertThat(cenario.getStatus()).isEqualTo(401);
    assertThat(token.getStatus()).isEqualTo(401);
    assertThat(token.getContentAsString()).doesNotContain("\"token\":");
    assertThat(contexto.getBeanDefinitionNames())
        .noneMatch(nome -> nome.toLowerCase().contains("desenvolvimento"));
  }

  @Test
  void deveManterOSwaggerDesligadoForaDoPerfilDev() throws Exception {
    assertThat(executar(get("/v3/api-docs")).getStatus()).isEqualTo(401);
    assertThat(executar(get("/swagger-ui/index.html")).getStatus()).isEqualTo(401);
    assertThat(executar(get("/swagger-ui.html")).getStatus()).isEqualTo(401);
    // E desligado no MVC: o springdoc nem registra o endpoint do OpenAPI.
    assertThat(contexto.getBeanNamesForType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class))
        .isEmpty();
  }

  @Test
  void deveAbrirOHealthSemDetalhesEFecharORestoSemToken() throws Exception {
    MockHttpServletResponse health = executar(get("/actuator/health"));
    MockHttpServletResponse api =
        executar(
            get(
                "/api/v1/upas/00000000-0000-0000-0000-000000000001/equipamentos/"
                    + "00000000-0000-0000-0000-000000000002/status"));
    MockHttpServletResponse outraRota = executar(get("/actuator/env"));

    assertThat(health.getStatus()).isEqualTo(200);
    assertThat(health.getContentAsString()).contains("\"status\"").doesNotContain("components");
    assertThat(api.getStatus()).isEqualTo(401);
    assertThat(api.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"upa\"");
    assertThat(outraRota.getStatus()).isEqualTo(401);
  }
}
