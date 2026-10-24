-- Spec 001, fatia 7a. Dados de leitura do lote de avisos. Nunca editar depois de aplicada.
-- Sem chave estrangeira entre agregados: paciente_id, upa_id e equipamento_id são só ids.
-- Quem grava estas tabelas (SISREG/CISREG) está fora do escopo; aqui o sistema só lê.
-- Limitação registrada: telefone, e-mail e nascimento ficam em texto, sem criptografia em repouso.

CREATE TABLE paciente (
    id              UUID         NOT NULL PRIMARY KEY,
    nome            VARCHAR(200) NOT NULL,
    telefone        VARCHAR(30),
    email           VARCHAR(254),
    data_nascimento DATE         NOT NULL,
    -- Equivalente a !isBlank() do Java: rejeita vazio e só espaços, tab ou quebra de linha.
    CONSTRAINT ck_paciente_telefone CHECK (telefone IS NULL OR telefone !~ '^\s*$'),
    CONSTRAINT ck_paciente_email CHECK (email IS NULL OR email !~ '^\s*$')
);

CREATE TABLE agendamento_exame (
    id             UUID        NOT NULL PRIMARY KEY,
    tipo           VARCHAR(20) NOT NULL,
    paciente_id    UUID        NOT NULL,
    upa_id         UUID        NOT NULL,
    equipamento_id UUID        NOT NULL,
    criado_em      TIMESTAMPTZ NOT NULL,
    inicio_exame   TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_agendamento_tipo CHECK (tipo IN ('TOMOGRAFIA', 'RAIO_X'))
);

CREATE INDEX ix_agendamento_equipamento_inicio
    ON agendamento_exame (equipamento_id, inicio_exame);
