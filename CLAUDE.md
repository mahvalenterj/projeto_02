# CLAUDE.md

Backend Java 21 LTS + Spring Boot 3.5 (Spring MVC, Tomcat embutido). Clean Architecture, DDD tático (Domain-Driven Design), SOLID, testes primeiro.

Detalhes e snippets: carregue só quando a tarefa pedir.

## Exemplo-guia: aviso de equipamento indisponível na UPA

Quando um equipamento (ex.: tomógrafo da UPA de Madureira) fica indisponível no SISREG, o sistema avisa os pacientes agendados para o dia, já com endereço alternativo, antes que eles cheguem e encontrem a porta fechada.

**Problema gerador:** como avisar todos os pacientes afetados a tempo, sob a restrição de que o canal de notificação pode falhar?

**Para quem e por que agora:** pacientes agendados para hoje, porque a manutenção é hoje e a falha só é útil de avisar antes do deslocamento.

### Fluxo (trigger → reação em cadeia)
1. **Trigger:** o status do equipamento muda para `INDISPONIVEL` (caso de uso `MarcarEquipamentoIndisponivel`).
2. O agregado `UPA` registra `EquipamentoIndisponivelEvent`; o evento é publicado após o commit (outbox).
3. `NotificarPacientesAfetados` busca os agendamentos **do dia** daquele equipamento.
4. Fila de prioridade: quem tem o exame mais cedo é notificado primeiro.
5. Cada notificação usa **retry com backoff e jitter** (Resilience4j). Esgotou as tentativas: grava `RevisaoManual` (**falha graciosa**: nunca some com o caso, nunca trava o lote inteiro).

### Modelo de domínio (linguagem ubíqua, em português)
- `UPA` (**Aggregate Root**): id, nome, endereço, equipamentos. Método `marcarEquipamentoIndisponivel(idEquipamento)` muda o status e registra o evento.
- `Equipamento` (entidade dentro do agregado `UPA`): id, tipo (`TOMOGRAFO`, `RAIO_X`), status.
- `StatusEquipamento` (enum): `DISPONIVEL`, `INDISPONIVEL`, `EM_MANUTENCAO`.
- `Paciente` (Aggregate Root próprio): id, nome, telefone, email.
- `AgendamentoExame` (**interface**): contrato comum de todo agendamento. Implementações: `AgendamentoTomografia`, `AgendamentoRaioX`, cada uma com suas regras de reagendamento. Referencia `Paciente`, `UPA` e `Equipamento` **por id**, nunca por objeto (outro agregado).
- `EquipamentoIndisponivelEvent` (record): idEquipamento, idUpa, momentoFalha (`Instant`).
- `ResultadoNotificacao` (`sealed interface`): `Enviada`, `Pendente`, `RevisaoManual`.

### Idempotência
Não se aplica neste exemplo: aviso duplicado incomoda, mas não causa dano. Revisar se o canal cobrar por mensagem ou se o evento puder ser reprocessado.

## Stack
- Java 21 LTS (Temurin), Maven (`./mvnw`). Se um starter exigir JDK 25 LTS, ajuste só `maven.compiler.release`.
- **Starter** = ponto de partida pronto (estrutura e configuração base, para não montar do zero).
- Spring Boot 3.5 com **Spring MVC** + **Tomcat embutido** (não usar WebFlux sem decisão registrada em ADR).
- JPA/Hibernate, Flyway, PostgreSQL 16+ (prod/testes), H2 só em `dev`.
- Testes: JUnit 5 (motor), Mockito (objetos falsos), AssertJ, WireMock 3 (erros e demoras propositais), Testcontainers, Pitest, JaCoCo, k6 (teste de carga).
- Resiliência: Resilience4j (circuit breaker, retry, bulkhead, rate limiter).
- Documentação de API: Springdoc OpenAPI (tipo Swagger), **só em `dev`**.

## Comandos
- Tudo (build, testes, cobertura): `./mvnw clean verify`
- Só testes: `./mvnw test`
- Mutação: `./mvnw org.pitest:pitest-maven:mutationCoverage`
- Rodar: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Formatar: `./mvnw spotless:apply`

**Nenhuma tarefa está pronta sem `./mvnw clean verify` verde.**

## Arquitetura (Clean / Hexagonal + DDD tático)
Dependências apontam para dentro: `web → application → domain`; `infrastructure` implementa as portas.
Pacotes por contexto, "gritando" o domínio (screaming architecture), não por camada técnica.

```
upa/
  domain/            UPA, Equipamento, StatusEquipamento, Paciente,
                     AgendamentoExame (interface), AgendamentoTomografia,
                     AgendamentoRaioX, EquipamentoIndisponivelEvent
                     PROIBIDO importar Spring, JPA, Jackson
  application/       casos de uso (1 por classe): MarcarEquipamentoIndisponivel,
                     NotificarPacientesAfetados
                     portas out: UPARepository, AgendamentoRepository,
                     NotificadorPaciente, RevisaoManualRepository
  infrastructure/    adapters: JPA, NotificadorSms (Resilience4j: @Retry, @Bulkhead),
                     listener do evento (chama o caso de uso)
  web/               controllers MVC, request/response DTOs, @RestControllerAdvice
```

- Controller só traduz HTTP ↔ caso de uso. Sem regra de negócio.
- O listener do evento fica em `infrastructure` e só chama o caso de uso. O domínio não conhece Spring.
- Entidade JPA ≠ entidade de domínio quando o domínio tem regra; o adapter converte.
- **Aggregate** = fronteira de consistência; acesso só pelo **Aggregate Root** (`UPA` para equipamentos). Repository e Factory isolam persistência e criação.
- **Bounded Context** próprio quando há linguagem, regra e dados próprios (ex.: `notificacoes` pode virar contexto separado). Começar monolítico modular e separar depois, com schema e Flyway próprios.
- Entre contextos: porta/evento, nunca join em tabela alheia. **Anti-Corruption Layer** ao integrar o CISREG ou o provedor de SMS.
- Injeção por construtor. `@Transactional` na camada `application`.

## SOLID (checklist de PR)
S: um motivo para mudar. O: novo tipo de exame = nova implementação de `AgendamentoExame`, não `if/else` por tipo. L: toda implementação respeita o contrato da interface. I: portas pequenas. D: domínio nunca importa infraestrutura.
Padrões quando o problema pedir: Strategy (regra de reagendamento por tipo de exame), Factory, Ports & Adapters, Chain of Responsibility.

## Estados, datas e eventos
- Momento = `Instant` (UTC) / `TIMESTAMPTZ`; JSON ISO-8601 UTC. "Agendamentos de hoje" = `LocalDate` calculado em `America/Sao_Paulo`. Injetar `Clock` para testar a virada de dia. Proibido `java.util.Date` e `LocalDateTime` para instantes.
- Resultado com várias saídas = `sealed interface` + records + `switch` **sem `default`** (novo subtipo quebra a compilação). Estado desconhecido vira `Pendente` + alerta. **Na dúvida, não marca como notificado (fail-safe).**
- `record` para DTOs, eventos e VOs. `class` para entidade JPA e quando precisa de herança/estado mutável.
- Imutabilidade: `List.copyOf(...)` em coleções; nenhum método de validação altera o objeto recebido.

## API REST (Spring MVC)
- Exemplo: `PATCH /api/v1/upas/{upaId}/equipamentos/{id}/status`.
- Contrato com records, fail-fast, Bean Validation; regra de negócio no domínio/caso de uso.
- `ResponseEntity` só quando precisar controlar status/headers.
- Erros: **ProblemDetail (RFC 9457)** via `@RestControllerAdvice`. Sem stack trace, nome de classe ou SQL.
- Dado de paciente de outra UPA/usuário = **404** igual ao inexistente (anti IDOR/BOLA).
- Versionamento por URI (`/api/v1`); uma spec OpenAPI por versão.
- Swagger UI e detalhes de `health` **só no profile `dev`**.

## Resiliência e carga
- **Retry** só para erro transitório (timeout, 5xx, 429 respeitando `Retry-After`), com backoff exponencial e **jitter**. Nunca em 4xx de validação.
- **Circuit breaker** no provedor de SMS: se está fora do ar, para de insistir e manda direto para `RevisaoManual`.
- **Bulkhead e RateLimiter** protegem o provedor; com virtual threads, sem limite você derruba o provedor sozinho.
- Timeout **no cliente HTTP** (conexão e leitura). Com chamada síncrona, `TimeLimiter` não é o timeout real.
- MVC + **virtual threads** (`spring.threads.virtual.enabled=true`) para I/O-bound; não ajudam em CPU-bound nem com **pinning** (`synchronized` longo).
- Backpressure: sinais = fila de notificações crescendo, p95/p99 subindo, pressão de heap/GC. Respostas: rate limit, 429/503 com `Retry-After`, bulkhead.
- WebFlux só com dezenas de milhares de conexões I/O-bound e equipe preparada; registrar em ADR.

## Persistência
- Postgres é a **fonte de verdade**. Mongo/Elasticsearch só como projeção, com consistência eventual assumida.
- N+1: `fetch join` e `hibernate.default_batch_fetch_size`; `batch_size` em escrita; índices em `(equipamento_id, data_exame)`.
- Dois operadores mudando o status do mesmo equipamento ao mesmo tempo: lock otimista (`@Version`), porque o conflito é raro.
- Eventos publicados via **outbox** na mesma transação da mudança de status.
- Migrations Flyway versionadas; nunca editar migration aplicada.

## Testes
- Pirâmide: **domínio** unitário puro e rápido → **caso de uso** (Mockito nas portas) → **web** (`@WebMvcTest`) → **integração** (Testcontainers com o **mesmo Postgres da produção**; nunca H2 como prova).
- Casos-chave: tomógrafo indisponível notifica só pacientes **do dia** e **daquele equipamento**; falha do canal após as tentativas gera `RevisaoManual`; um paciente com falha não impede o aviso dos outros.
- **WireMock** para o provedor de SMS: 500, lento, 401, 429 (`Retry-After`), `CONNECTION_RESET_BY_PEER`, corpo ilegível, resposta fora do contrato. Stub nunca vai no código de produção.
- **Mutation testing** (Pitest, MSI ≥ 85%). Cobertura de linha sozinha não prova nada. JaCoCo ≥ 80% como piso.
- JUnit 5: `@TestFactory` para dados em CSV, `@Tag("slow")` para pipeline paralelo, `assertTimeoutPreemptively` como orçamento.
- **Performance budget** no CI (k6): simular muitos agendamentos afetados de uma vez (ex.: 5.000 notificações); p95 ≤ 300 ms por chamada ao adapter e erro < 1%. Estourou, o build falha.
- Nome: `deveNotificarPacientesQuandoTomografoFicarIndisponivel`. Arrange / Act / Assert. TDD: vermelho → mínimo → refatorar; um commit por fatia.

## Segurança
- Segredos **só** em variáveis de ambiente ou GitHub Secrets (token do provedor de SMS, credenciais do CISREG). `.env` no `.gitignore`; `.env.example` com aviso.
- Dados de paciente (nome, telefone, exame) são sensíveis: nada disso em log. Consultas parametrizadas.
- Falha fechada: se não dá para garantir segurança, responde 503 em vez de seguir.
- Revisão de código (PR): feedback em tom de sugestão, com motivo.

## Documentação
- Decisão de arquitetura → ADR em `docs/adr/NNNN-titulo.md` (contexto, decisão, consequências, alternativas, trade-offs).
- Diagramas C4 em `docs/architecture/`. Event Storming ao separar contextos.
- Toda feature nasce de spec em `docs/specs/` (`_TEMPLATE.md`). Seção que não se aplica ao problema: marcar "não aplicável" com uma linha de justificativa, nunca forçar.
- README: como rodar, variáveis, como testar, **o que um sistema real teria a mais** (simplificações propositais).
- Padrões de resiliência e idempotência detalhados ficam fora do escopo agora; entram em `docs/standards/` quando um projeto de pagamento precisar.

## Fluxo de trabalho
1. **Grill**: me interrogue até não haver ambiguidade (inclua as perguntas "E se...": e se o paciente não tem telefone? e se o SMS cair? e se o equipamento voltar antes do aviso?).
2. **Spec** com critérios de aceite verificáveis.
3. **Plano** em plan mode: arquivos, portas, testes. Aguardar aprovação.
4. **Fatias pequenas**: teste de aceitação primeiro, depois código; um commit por fatia. Dentro de cada fatia, seguir a ordem: domínio → interfaces/portas → repository → service/caso de uso → controller. Não gerar camadas fora de ordem nem pular etapa, mesmo que pareça mais rápido.
5. **Verificação**: `./mvnw clean verify`, checklist SOLID, subagent de review.
6. Eu reviso o diff e abro o PR.

## Nunca faça
- Regra de negócio em controller ou entidade JPA.
- `org.springframework.*` ou `jakarta.persistence.*` em `domain/`.
- Referenciar outro agregado por objeto em vez de id.
- `switch` com `default` sobre `sealed` só para "calar" o compilador.
- Marcar paciente como notificado sem confirmação do canal.
- Retry em 4xx ou erro de validação; retry sem backoff e jitter; loop de retry manual com `while` (usar Resilience4j).
- `catch (Exception)` que engole erro: toda falha vira log estruturado + `RevisaoManual`.
- Expor stack trace, SQL ou entidade na resposta; Swagger em produção.
- Dado de paciente em log; commitar segredo ou `.env`; `System.out.println` (usar SLF4J).
- Desativar ou apagar teste para passar o build; refatorar fora do escopo da spec.

## Convenções
- Pacotes minúsculos; classes `PascalCase`; casos de uso com verbo (`MarcarEquipamentoIndisponivel`).
- Idioma do código: domínio em português (linguagem ubíqua: `Paciente`, `Equipamento`, `AgendamentoExame`); termos técnicos de framework em inglês.
- Commits: Conventional Commits. Branch: `feat/<spec-id>-slug`.

## Ainda não coberto (completar após ler)
Aulas 6 a 9 do módulo: Docker e CI/CD, Observabilidade em tempo real, Segurança (OWASP Top 10, Zero Trust), IA aplicada com privacidade. Adicionar aqui quando lidas.