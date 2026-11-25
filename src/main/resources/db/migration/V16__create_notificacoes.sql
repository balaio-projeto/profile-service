CREATE TABLE notificacoes (
    id UUID PRIMARY KEY,
    conta_id UUID NOT NULL REFERENCES contas(id) ON DELETE CASCADE,
    tipo VARCHAR(40) NOT NULL,
    titulo VARCHAR(120) NOT NULL,
    mensagem VARCHAR(500) NOT NULL,
    link VARCHAR(300),
    chave VARCHAR(160) UNIQUE,
    lida_em TIMESTAMPTZ,
    criada_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notificacoes_conta ON notificacoes(conta_id, criada_em DESC);
CREATE INDEX idx_notificacoes_nao_lidas ON notificacoes(conta_id) WHERE lida_em IS NULL;
