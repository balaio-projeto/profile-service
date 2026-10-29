CREATE TABLE controle_administracao (
    id SMALLINT PRIMARY KEY,
    bootstrap_concluido BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_controle_administracao_singleton CHECK (id = 1)
);

INSERT INTO controle_administracao (id, bootstrap_concluido) VALUES (1, FALSE);
