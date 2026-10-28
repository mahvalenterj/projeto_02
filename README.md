# Aviso de equipamento indisponível na UPA

Quando um tomógrafo ou um raio-X de uma UPA fica fora do ar, os pacientes com exame marcado naquele dia precisam ser avisados **a tempo**, antes de saírem de casa. O canal de aviso (SMS, depois e-mail) pode falhar. Este projeto resolve esse problema com três garantias:

- **Nenhum paciente perdido:** se o SMS e o e-mail falharem, o caso vai para uma fila de revisão manual.
- **Nenhum aviso duplicado:** mesmo com o evento reprocessado ou duas execuções simultâneas, cada paciente recebe no máximo um aviso por evento.
- **A tempo:** 99% dos avisos com desfecho em até 5 minutos, medido com 5.000 avisos.

Backend em **Java 21 e Spring Boot 3.5**, com Clean Architecture, DDD tático e testes primeiro.

## Pré-requisitos

- **JDK 21** (`java -version` deve mostrar 21).
- **Docker** em execução (os testes de integração sobem um Postgres real com Testcontainers).
- Não é preciso instalar Maven: use o wrapper `./mvnw` (no Windows, `mvnw.cmd`).

## 1. Rodar os testes

```bash
./mvnw clean verify
```

Roda todos os testes de unidade e de integração (Postgres real e provedores de SMS e e-mail simulados com WireMock), o formatador (Spotless) e a checagem de cobertura do JaCoCo (mínimo de 80%). Leva alguns minutos.

Teste de mutação do domínio (Pitest, mínimo de 85%):

```bash
./mvnw org.pitest:pitest-maven:mutationCoverage
```

## 2. Rodar o teste de carga (5.000 avisos)

```bash
./mvnw test -Pcarga
```

Grava 5.000 agendamentos, marca o equipamento como indisponível e despacha o lote contra provedores simulados com latência de 50 a 150 ms e falhas 503 injetadas. Ele afirma:

- 99% dos avisos com desfecho em até 5 minutos;
- erro abaixo de 1%;
- p95 da requisição HTTP ao provedor ≤ 300 ms;
- p99 de cada envio abaixo de 2 s;
- nenhum envio esgotado por falha local;
- nenhum dado de paciente em todo o log da rodada.

O relatório com os números fica em `target/carga/relatorio.txt`. Ele não roda no `verify` comum porque leva alguns minutos.

## 3. Rodar a aplicação e testar à mão

**a) Suba o banco e o provedor simulado:**

```bash
docker compose -f docker-compose.dev.yml up -d
```

Isso sobe um Postgres 16 e um WireMock na porta 8089, que faz o papel dos provedores de SMS e e-mail. Nenhuma mensagem real é enviada.

**b) Suba a aplicação no perfil de desenvolvimento:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

O perfil `dev` já aponta para o banco e o WireMock do passo anterior e usa um par de chaves **só de desenvolvimento** (`config/dev/jwt/`) para os tokens de teste. Rode o comando **a partir da raiz do projeto**, porque a chave de dev é lida por caminho relativo. Fora do perfil `dev`, a aplicação não sobe sem as variáveis de ambiente: banco (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`), canais (`UPA_CANAIS_SMS_BASE_URL`, `UPA_CANAIS_SMS_TOKEN`, `UPA_CANAIS_EMAIL_BASE_URL`, `UPA_CANAIS_EMAIL_TOKEN`) e JWT (`UPA_SEGURANCA_JWT_CHAVE_PUBLICA`, `UPA_SEGURANCA_JWT_EMISSOR`, `UPA_SEGURANCA_JWT_AUDIENCIA`).

> **Atenção ao horário:** o aviso vale para os exames **de hoje que ainda não começaram** (fuso de São Paulo). Perto da meia-noite, o cenário sintético cria exames poucos minutos à frente, e um `PATCH` feito depois deles (ou depois da meia-noite) não avisa ninguém. Durante o dia não há esse problema.

**c) Siga o roteiro.** O arquivo [`requisicoes.http`](requisicoes.http) tem todas as chamadas prontas (extensão REST Client do VS Code ou IntelliJ). O Swagger fica em http://localhost:8080/swagger-ui/index.html, só no perfil `dev`: gere o token no passo 2 e cole em **Authorize** antes de chamar o `PATCH`. O mesmo roteiro com `curl`:

1. **Criar um cenário sintético** (UPA, tomógrafo e pacientes com exame hoje):
   ```bash
   curl -s -X POST http://localhost:8080/dev/cenario
   ```
   Guarde o `upaId` e o `equipamentoId` da resposta.
2. **Gerar um token de operador** para essa UPA:
   ```bash
   curl -s -X POST http://localhost:8080/dev/token -H "Content-Type: application/json" -d '{"upas":["<upaId>"]}'
   ```
3. **Marcar o tomógrafo como indisponível** (responde 204):
   ```bash
   curl -i -X PATCH http://localhost:8080/api/v1/upas/<upaId>/equipamentos/<equipamentoId>/status \
     -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"status":"INDISPONIVEL"}'
   ```
4. **Ver os avisos saindo:** em poucos segundos o despachante processa o evento, e as mensagens aparecem em http://localhost:8089/__admin/requests.
5. **Ver os erros do contrato:**
   - outra UPA no caminho dá `404`, com o mesmo corpo de um recurso inexistente;
   - `{"status":"EM_MANUTENCAO"}` dá `422`;
   - sem token, `401`.

   Os erros saem em ProblemDetail (RFC 9457), sem stack trace.

Para desligar: `Ctrl+C` na aplicação e `docker compose -f docker-compose.dev.yml down`.

## Como o fluxo funciona

1. O operador chama o `PATCH`. O caso de uso muda o status e grava o evento no **outbox, na mesma transação**: o evento só existe se o status mudou.
2. Um agendador pega um evento por vez do outbox (`FOR UPDATE SKIP LOCKED` com lease de 5 min) e roda o lote **fora da transação**.
3. O lote seleciona os agendamentos do dia (fuso de São Paulo), ordena por horário, idade e antiguidade e, para cada paciente:
   - reserva o aviso no banco antes de enviar, com chave única `(evento, agendamento)`;
   - relê o status do equipamento, para não avisar se ele voltou;
   - tenta o SMS, depois o e-mail, e por fim manda para revisão manual.
4. Cada canal usa retry com backoff e jitter (só para erro transitório), circuit breaker, limitador de 50/s e bulkhead (Resilience4j). Uma resposta fora do contrato nunca é marcada como confirmada. Cada envio leva o cabeçalho `Idempotency-Key`, para o provedor não duplicar uma mensagem repetida.

Os diagramas estão em [`docs/architecture/`](docs/architecture/README.md).

## Organização do código

| Pacote | Papel |
|---|---|
| `domain` | Regras puras: UPA, equipamento, seleção e ordem dos avisos, reserva, resultado. Sem Spring, JPA nem Jackson. |
| `application` | Casos de uso e portas. |
| `infrastructure` | Adapters: Postgres (JPA e Flyway), canais HTTP de SMS e e-mail, outbox e configuração, incluindo a validação do JWT (`infrastructure/config`). Em `infrastructure/dev`, as rotas `/dev`, que só existem no perfil `dev`. |
| `web` | Controller, autorização por UPA e erros em ProblemDetail. |

## Limites conhecidos

- **A emissão real de tokens fica fora do sistema.** A aplicação só valida JWT RS256 com a chave pública. O `/dev/token` existe só para teste, no perfil `dev`.
- **Uma réplica.** Com várias réplicas, o limitador de 50/s valeria por réplica e o lease usaria o relógio de cada uma. Suportar isso é o próximo passo.
- **A garantia contra mensagem duplicada no provedor** depende de o provedor real honrar o `Idempotency-Key`. Só o simulador está provado.
- **A privacidade de log foi provada no nível INFO.** Ligar TRACE de JDBC em produção imprimiria parâmetros de consulta.
- Os números de carga são desta máquina de desenvolvimento (2 núcleos). Em outro hardware eles mudam.
