ALTER TABLE contas ADD COLUMN imagem VARCHAR(1024);
ALTER TABLE contas ADD CONSTRAINT ck_contas_imagem CHECK (imagem IS NULL OR imagem <> '');

ALTER TABLE midias_perfil ALTER COLUMN perfil_id DROP NOT NULL;
ALTER TABLE midias_perfil ADD COLUMN conta_id UUID;
ALTER TABLE midias_perfil ADD CONSTRAINT fk_midias_perfil_conta
    FOREIGN KEY (conta_id) REFERENCES contas(id) ON DELETE RESTRICT;
ALTER TABLE midias_perfil ADD CONSTRAINT ck_midias_perfil_dono
    CHECK (num_nonnulls(perfil_id, conta_id) = 1);
CREATE INDEX ix_midias_perfil_conta ON midias_perfil (conta_id) WHERE conta_id IS NOT NULL;
