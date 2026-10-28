# Estados do aviso e dos resultados

Tudo aqui é **domínio já feito e commitado** (fatias 1b e 3), e a persistência dos estados no banco também (fatia 4, commit `739a24e`). O que ainda é planejado é o alerta de `Indeterminada` (camada de aplicação).

## 1. Estados da linha do aviso (`StatusAviso`)

Só `PENDENTE` evolui; `CONFIRMADO` e `CANCELADO` são terminais.

```mermaid
stateDiagram-v2
  [*] --> PENDENTE : reservar, INSERT ON CONFLICT DO NOTHING
  PENDENTE --> PENDENTE : reassumir, reserva abandonada
  PENDENTE --> PENDENTE : resultado Indeterminada
  PENDENTE --> PENDENTE : resultado RevisaoManual
  PENDENTE --> CONFIRMADO : resultado Enviada
  PENDENTE --> CANCELADO : resultado Cancelada EQUIPAMENTO_RESTABELECIDO
  CONFIRMADO --> [*]
  CANCELADO --> [*]
```

`CONFIRMADO` só acontece com a confirmação do canal. Na dúvida, o aviso fica `PENDENTE`.

## 2. O que cada resultado de uma tentativa (`ResultadoNotificacao`) provoca

`ResultadoNotificacao` é uma `sealed interface`; o código usa `switch` **sem `default`**, então um novo subtipo quebra a compilação até ser tratado.

```mermaid
flowchart LR
  classDef feito fill:#d4edda,stroke:#2e7d32,color:#111
  classDef planejado fill:#eceff1,stroke:#607d8b,color:#111,stroke-dasharray: 6 4

  enviada["Enviada"]:::feito --> confirmado["Linha do aviso: CONFIRMADO"]:::feito
  cancelada["Cancelada<br/>EQUIPAMENTO_RESTABELECIDO"]:::feito --> cancelado["Linha do aviso: CANCELADO<br/>sem revisão manual"]:::feito
  indeterminada["Indeterminada<br/>estado desconhecido do canal"]:::feito --> pendente1["Linha continua PENDENTE<br/>nunca vira CONFIRMADO"]:::feito
  pendente1 -.-> alerta["Alerta e reprocessamento<br/>após o tempo-limite"]:::planejado
  revisao["RevisaoManual<br/>SEM_CONTATO ou CANAIS_ESGOTADOS"]:::feito --> pendente2["Linha continua PENDENTE"]:::feito
  revisao --> item["Item gravado em revisao_manual<br/>a revisão manual vence na reentrega"]:::feito
```

## 3. Decisão por agendamento (`PoliticaDeReserva`)

A ordem das regras importa: a **revisão manual é consultada primeiro**.

```mermaid
flowchart TD
  inicio(["decidir por agendamento"]) --> revisao{"já está em<br/>revisão manual?"}
  revisao -- "sim" --> pularRev["Pular: EM_REVISAO_MANUAL"]
  revisao -- "não" --> existe{"existe aviso<br/>para o evento?"}
  existe -- "não" --> reservar["Reservar"]
  existe -- "sim" --> status{"status do aviso"}
  status -- "CONFIRMADO" --> pularConf["Pular: JA_CONFIRMADO"]
  status -- "CANCELADO" --> pularCanc["Pular: JA_CANCELADO"]
  status -- "PENDENTE" --> abandonada{"mais antigo que<br/>o tempo-limite?"}
  abandonada -- "sim" --> reassumir["Reassumir"]
  abandonada -- "não, inclusive no limite exato" --> pularAnd["Pular: EM_ANDAMENTO"]
```

O valor do tempo-limite ainda está **em aberto** (depende de tentativas, backoff e timeout). A política só recebe a `Duration` como parâmetro, estritamente positiva.

## 4. Estados do equipamento (`StatusEquipamento`)

Só `DISPONIVEL` e `INDISPONIVEL` se alternam. Repetir o mesmo status é inválido. `EM_MANUTENCAO` está fora do fluxo desta spec.

```mermaid
stateDiagram-v2
  [*] --> DISPONIVEL
  DISPONIVEL --> INDISPONIVEL : marcarEquipamentoIndisponivel, registra o evento
  INDISPONIVEL --> DISPONIVEL : marcarEquipamentoDisponivel, sem evento
  EM_MANUTENCAO --> EM_MANUTENCAO : sem transição por esta API
```

## O que foi conferido no código para desenhar

- Transições do aviso e efeito de cada resultado: `domain/StatusAviso.java` e `domain/AvisoPaciente.java` (métodos `aplicar`, `estaAbandonada` e `reassumir`).
- Subtipos dos resultados e motivos: `domain/ResultadoNotificacao.java`, `MotivoCancelamento.java`, `MotivoRevisaoManual.java`.
- Ordem das regras: `domain/PoliticaDeReserva.java` e `MotivoDoPulo.java`.
- Transições do equipamento: `domain/StatusEquipamento.java` (tabela com `switch` sem `default`).
