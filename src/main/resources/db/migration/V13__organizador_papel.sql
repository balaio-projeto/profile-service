-- PI-210: amplia o conjunto de papéis aceitos para incluir 'organizador'.
-- Migration aditiva: a constraint original (V1) é substituída, sem editar a migration anterior.
ALTER TABLE contas DROP CONSTRAINT ck_contas_papel;

ALTER TABLE contas
    ADD CONSTRAINT ck_contas_papel CHECK (papel IN ('comprador', 'artesao', 'admin', 'organizador'));
