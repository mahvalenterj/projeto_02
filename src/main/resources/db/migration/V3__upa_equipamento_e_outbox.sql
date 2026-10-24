-- Spec 001, fatia 7b. Nunca editar depois de aplicada.
-- Sem chave estrangeira entre agregados: equipamento.upa_id é só um id.

CREATE TABLE upa (
    id   UUID         NOT NULL PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    CONSTRAINT ck_upa_nome CHECK (length(btrim(nome)) > 0)
);

CREATE TABLE equipamento (
    id      UUID        NOT NULL PRIMARY KEY,
    upa_id  UUID        NOT NULL,
    tipo    VARCHAR(20) NOT NULL,
    status  VARCHAR(20) NOT NULL,
    version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_equipamento_tipo CHECK (tipo IN ('TOMOGRAFO', 'RAIO_X')),
    CONSTRAINT ck_equipamento_status CHECK (status IN ('DISPONIVEL', 'INDISPONIVEL', 'EM_MANUTENCAO'))
);

CREATE INDEX ix_equipamento_upa ON equipamento (upa_id);

CREATE TABLE upa_endereco_alternativo (
    upa_id UUID         NOT NULL,
    tipo   VARCHAR(20)  NOT NULL,
    texto  VARCHAR(500) NOT NULL,
    PRIMARY KEY (upa_id, tipo),
    CONSTRAINT ck_endereco_tipo CHECK (tipo IN ('TOMOGRAFO', 'RAIO_X')),
    CONSTRAINT ck_endereco_texto CHECK (length(btrim(texto)) > 0)
);

-- id = eventoId. reivindicado_ate serve de lease (despachante vivo) e de atraso de reentrega.
CREATE TABLE outbox_evento (
    id               UUID         NOT NULL PRIMARY KEY,
    tipo             VARCHAR(60)  NOT NULL,
    payload          JSONB        NOT NULL,
    ocorrido_em      TIMESTAMPTZ  NOT NULL,
    situacao         VARCHAR(20)  NOT NULL DEFAULT 'PENDENTE',
    processado_em    TIMESTAMPTZ,
    reivindicado_ate TIMESTAMPTZ,
    tentativas       INTEGER      NOT NULL DEFAULT 0,
    tipo_ultimo_erro VARCHAR(200),
    CONSTRAINT ck_outbox_situacao CHECK (situacao IN ('PENDENTE', 'PROCESSADO', 'ESGOTADO')),
    CONSTRAINT ck_outbox_processado CHECK ((situacao = 'PROCESSADO') = (processado_em IS NOT NULL)),
    CONSTRAINT ck_outbox_tentativas CHECK (tentativas >= 0)
);

CREATE INDEX ix_outbox_nao_processado
    ON outbox_evento (ocorrido_em, id)
    WHERE situacao = 'PENDENTE';
