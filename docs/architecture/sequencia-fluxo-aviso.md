# Sequência: do PATCH do operador ao aviso ao paciente

Este é o fluxo **completo previsto pela spec 001**. Só alguns participantes existem hoje. Cada participante diz no nome se está **feito** (e commitado) ou **planejado**; as fases abaixo dizem o mesmo.

- Feitos e commitados: `UPA`, `SelecaoDeAvisos`, `PoliticaDeReserva` (domínio).
- Feitos (fatia 4, commit `739a24e`): as portas e os adapters de `aviso_paciente` e `revisao_manual`.
- Planejados: controller, casos de uso, outbox, listener, repositório de UPA e agendamento, adapters de SMS e e-mail.

```mermaid
sequenceDiagram
  autonumber
  actor Op as Operador
  participant Web as Controller PATCH (planejado)
  participant UC1 as MarcarEquipamentoIndisponivel (planejado)
  participant Upa as UPA (domínio, feito)
  participant Out as Outbox e listener (planejado)
  participant UC2 as NotificarPacientesAfetados (planejado)
  participant Sel as SelecaoDeAvisos (domínio, feito)
  participant Pol as PoliticaDeReserva (domínio, feito)
  participant Avs as AvisoPacienteRepository (feito)
  participant Rev as RevisaoManualRepository (feito)
  participant Can as Canais SMS e e-mail (planejado)

  rect rgba(120, 144, 156, 0.18)
    Note over Op,Out: Fase A - gatilho e evento (planejado, exceto a UPA)
    Op->>Web: PATCH status INDISPONIVEL
    Web->>UC1: marcar equipamento
    UC1->>Upa: marcarEquipamentoIndisponivel(equipamento, eventoId, momentoFalha)
    Upa-->>UC1: status INDISPONIVEL e evento registrado
    UC1->>Out: grava o evento na mesma transação (outbox)
    UC1-->>Web: ok
    Web-->>Op: 200, ou 404 e 409 em ProblemDetail
  end

  rect rgba(120, 144, 156, 0.18)
    Note over Out,Rev: Fase B - seleção do lote (caso de uso planejado, regras feitas)
    Out->>UC2: evento publicado após o commit
    UC2->>Sel: selecionar(candidatos, equipamento, momentoFalha)
    Sel-->>UC2: agendamentos do dia da falha, futuros, em ordem
    UC2->>Avs: buscarPorEvento(eventoId)
    UC2->>Rev: agendamentosEmRevisao(eventoId)
  end

  loop para cada agendamento do lote
    UC2->>Pol: decidir(aviso existente, emRevisaoManual, agora, tempoLimite)
    Pol-->>UC2: Reservar, Reassumir ou Pular
    alt Pular (já confirmado, cancelado, em andamento ou em revisão manual)
      Note over UC2: segue para o próximo agendamento
    else Reservar ou Reassumir
      rect rgba(46, 125, 50, 0.15)
        Note over UC2,Avs: Fase C - reserva no banco (feito)
        UC2->>Avs: reservar (INSERT ON CONFLICT) ou reassumir (UPDATE condicional)
        Avs-->>UC2: true só para a execução vencedora
        Note over UC2,Avs: a reserva é commitada ANTES de chamar o canal
      end
      rect rgba(120, 144, 156, 0.18)
        Note over UC2,Can: Fase D - releitura, envio e fallback (planejado)
        UC2->>UC2: relê o status do equipamento, sem cache
        alt equipamento voltou a DISPONIVEL
          UC2->>Avs: registrarDesfecho CANCELADO (EQUIPAMENTO_RESTABELECIDO)
        else equipamento segue INDISPONIVEL
          UC2->>Can: SMS com retry, backoff com jitter e circuit breaker
          alt SMS confirmado
            UC2->>Avs: registrarDesfecho CONFIRMADO
          else SMS esgotado ou circuit breaker aberto
            UC2->>Can: e-mail como fallback
            alt e-mail confirmado
              UC2->>Avs: registrarDesfecho CONFIRMADO
            else e-mail falhou
              UC2->>Rev: registrar item (CANAIS_ESGOTADOS)
            end
          end
        end
        Note over UC2,Rev: sem telefone usa e-mail, sem nenhum contato registra SEM_CONTATO
        Note over UC2,Avs: resposta ilegível vira Indeterminada, o aviso fica PENDENTE e gera alerta
      end
    end
  end
```

## Pontos que o diagrama mostra de propósito

- **Reserva antes do envio, commitada antes do canal:** é o que impede duas execuções de enviar para o mesmo paciente, e nenhuma transação fica aberta durante a chamada HTTP.
- **Quem decide o vencedor é o banco:** `reservar` e `reassumir` devolvem `true` só para uma execução, mesmo com disputa simultânea.
- **Revisão manual não muda a linha do aviso:** o aviso continua `PENDENTE` e a revisão manual vence na reentrega do evento.
- **`Indeterminada` nunca conta como notificado:** na dúvida, não se marca `CONFIRMADO`.

## O que foi conferido no código para desenhar

- Seleção, ordem, dia da falha e horário futuro: `domain/SelecaoDeAvisos.java`.
- Ordem das regras de pular, reservar e reassumir: `domain/PoliticaDeReserva.java` e `domain/DecisaoParaAgendamento.java`.
- Efeito de cada resultado no aviso: `domain/AvisoPaciente.aplicar` e `domain/ResultadoNotificacao.java`.
- Operações condicionais no banco: `infrastructure/persistence/AvisoPacienteJpaRepository.java` e a Javadoc de `application/AvisoPacienteRepository.java`.
- Parte planejada: spec 001, §5 (fluxo), §6 (casos "E se...") e §10 (resiliência).
