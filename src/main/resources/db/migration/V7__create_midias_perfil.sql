CREATE TABLE midias_perfil (
    id UUID PRIMARY KEY,
    perfil_id UUID NOT NULL REFERENCES perfis_artesao(id) ON DELETE CASCADE,
    chave VARCHAR(240) NOT NULL UNIQUE,
    tipo VARCHAR(40) NOT NULL,
    tamanho INTEGER NOT NULL CHECK (tamanho BETWEEN 1 AND 2097152),
    sha256 CHAR(64) NOT NULL,
    estado VARCHAR(10) NOT NULL CHECK (estado IN ('PENDING', 'ACTIVE', 'DELETE')),
    criada_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_midias_perfil_limpeza ON midias_perfil (estado, criada_em);
