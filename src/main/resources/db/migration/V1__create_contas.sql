CREATE TABLE contas (
    id UUID PRIMARY KEY,
    nome VARCHAR(240) NOT NULL,
    email VARCHAR(254) NOT NULL,
    senha_hash VARCHAR(255) NOT NULL,
    papel VARCHAR(16) NOT NULL,
    troca_senha_obrigatoria BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_contas_nome CHECK (nome = btrim(nome) AND nome <> ''),
    CONSTRAINT ck_contas_email CHECK (
        email = lower(btrim(email))
        AND email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
    ),
    CONSTRAINT ck_contas_senha_hash CHECK (
        senha_hash ~ '^\{bcrypt\}\$2[aby]\$(0[4-9]|[12][0-9]|3[01])\$[./A-Za-z0-9]{53}$'
    ),
    CONSTRAINT ck_contas_papel CHECK (papel IN ('comprador', 'artesao', 'admin'))
);

CREATE UNIQUE INDEX uk_contas_email_normalizado ON contas (lower(btrim(email)));
