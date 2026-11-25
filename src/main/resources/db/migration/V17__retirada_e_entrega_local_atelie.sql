ALTER TABLE configuracoes_atelie ADD COLUMN aceita_retirada BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE configuracoes_atelie ADD COLUMN aceita_entrega_local BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE configuracoes_atelie ADD COLUMN retirada_bairro VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE configuracoes_atelie ADD COLUMN retirada_cidade VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE configuracoes_atelie ADD CONSTRAINT ck_configuracoes_atelie_retirada
    CHECK (NOT aceita_retirada OR (btrim(retirada_bairro) <> '' AND btrim(retirada_cidade) <> ''));
ALTER TABLE configuracoes_atelie ADD CONSTRAINT ck_configuracoes_atelie_entrega_local
    CHECK (NOT aceita_entrega_local OR cep_origem <> '');
