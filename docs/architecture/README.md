# Arquitetura: aviso de equipamento indisponível na UPA

Material de apresentação do projeto. Os diagramas são Mermaid e renderizam no VS Code (preview de Markdown com suporte a Mermaid) e no GitHub.

**Estado conferido no repositório em 2026-10-27** (branch `feat/001-aviso-equipamento-indisponivel`). Os diagramas 1 a 4 mostram o estado de 2026-10-15 e marcam como planejado o que depois foi feito (a tabela abaixo é a fonte atual); o diagrama 5 está atualizado.

## Legenda usada em todos os diagramas

| Marca | Significado |
|---|---|
| Verde, borda cheia | **Feito e commitado** |
| Cinza, borda tracejada | **Planejado**, ainda não existe no código |
| Azul | Pessoa ou sistema externo |

## Fatias da spec 001: o que existe de verdade

| Fatia | Conteúdo | Situação | Commit |
|---|---|---|---|
| 0 | Bootstrap Maven, Spotless, JaCoCo, Pitest | Feita | `fb2ed41` |
| 1 | Domínio da indisponibilidade (`UPA`, `Equipamento`, evento) | Feita | `17f478a` |
| 1b | Retorno do equipamento a `DISPONIVEL` | Feita | `c91a9f8` |
| 2 | Seleção e ordenação dos agendamentos | Feita | `e48cc75` |
| 3 | Resultado do aviso e política de reserva | Feita | `3799d87` |
| 3b | Mensagem com endereço alternativo | Feita | `a95e22a` |
| 4 | Persistência de `aviso_paciente` e `revisao_manual` (Flyway, JPA, Testcontainers) | Feita | `739a24e` |
| 5 | Caso de uso `NotificarPacientesAfetados` | Feita | `1f05011` |
| 6 | Adapters de SMS e e-mail com Resilience4j | Feita | `c98a96c` |
| 6b | Chave de idempotência até o provedor | Feita | `9609527` |
| 7a | Leitura de pacientes e agendamentos | Feita | `3dd4171` |
| 7b | UPA, equipamento, outbox e despachante | Feita | `12a05bd` |
| 7c | Fiação, agendador e teste de ponta a ponta | Feita | `ead4792` |
| 8 | Controller `PATCH`, `ProblemDetail`, autorização por UPA com JWT RS256 e kit de desenvolvimento | Feita | `b22b820` |
| 9 | Privacidade em log e teste de carga (Java, `-Pcarga`) | Feita | `0f32cd3` |

Hoje existem: 26 classes em `domain/`, 30 em `application/`, 11 em `web/` e, em `infrastructure/`, 25 em `persistence/`, 6 em `canais/`, 12 em `config/` e 5 em `dev/` (só no perfil `dev`). O fluxo roda de ponta a ponta: `PATCH`, evento gravado, outbox, despachante e canais.

## Diagramas

1. [C4: contêineres e componentes](c4-containers-e-componentes.md)
2. [Sequência do fluxo completo](sequencia-fluxo-aviso.md)
3. [Modelo ER das tabelas existentes](er-banco.md)
4. [Estados do aviso e dos resultados](estados-aviso-e-resultados.md)
5. [Canais, outbox e despachante](canais-outbox-despachante.md)

## Cuidados

- Nenhum diagrama contém dado de paciente, segredo ou o texto real da mensagem enviada.
- Os diagramas não foram renderizados por uma ferramenta nesta sessão (não instalei `mermaid-cli`). Confira no preview do VS Code ou no GitHub.
