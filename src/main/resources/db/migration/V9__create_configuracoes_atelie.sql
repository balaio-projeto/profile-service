CREATE TABLE configuracoes_atelie (
    perfil_id UUID PRIMARY KEY REFERENCES perfis_artesao(id) ON DELETE CASCADE,
    cep_origem VARCHAR(9) NOT NULL DEFAULT '',
    prazo_padrao_dias INTEGER NOT NULL DEFAULT 1 CHECK (prazo_padrao_dias BETWEEN 1 AND 120),
    aceita_encomendas BOOLEAN NOT NULL DEFAULT FALSE,
    encomendas_pausadas BOOLEAN NOT NULL DEFAULT FALSE,
    chave_pix TEXT NOT NULL DEFAULT '',
    CHECK (cep_origem = '' OR cep_origem ~ '^[0-9]{5}-[0-9]{3}$')
);
