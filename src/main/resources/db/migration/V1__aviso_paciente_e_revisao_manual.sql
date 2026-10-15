-- Spec 001, fatia 4 (ADR 0001). Nunca editar depois de aplicada.
-- Sem chave estrangeira para o agendamento: estes dados são do contexto de notificações
-- e guardam agendamento_id só como id.

CREATE TABLE aviso_paciente (
    evento_id           UUID        NOT NULL,
    agendamento_id      UUID        NOT NULL,
    status              VARCHAR(20) NOT NULL,
    motivo_cancelamento VARCHAR(40),
    reservado_em        TIMESTAMPTZ NOT NULL,
    atualizado_em       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (evento_id, agendamento_id),
    CONSTRAINT ck_aviso_status CHECK (status IN ('PENDENTE', 'CONFIRMADO', 'CANCELADO')),
    CONSTRAINT ck_aviso_motivo CHECK ((status = 'CANCELADO') = (motivo_cancelamento IS NOT NULL)),
    CONSTRAINT ck_aviso_datas CHECK (atualizado_em >= reservado_em)
);

CREATE TABLE revisao_manual (
    evento_id      UUID        NOT NULL,
    agendamento_id UUID        NOT NULL,
    motivo         VARCHAR(40) NOT NULL,
    registrado_em  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (evento_id, agendamento_id)
);
