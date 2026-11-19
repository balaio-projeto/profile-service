-- Preserve object keys until physical cleanup has been confirmed.
ALTER TABLE midias_perfil DROP CONSTRAINT midias_perfil_perfil_id_fkey;
ALTER TABLE midias_perfil ADD CONSTRAINT fk_midias_perfil_perfil
    FOREIGN KEY (perfil_id) REFERENCES perfis_artesao(id) ON DELETE RESTRICT;

-- Persist the retry/claim deadline so failures and process restarts do not
-- repeatedly select the same batch or lose unfinished deletions.
ALTER TABLE midias_perfil ADD COLUMN proxima_tentativa TIMESTAMPTZ
    NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX ix_midias_perfil_proxima_tentativa
    ON midias_perfil (proxima_tentativa, criada_em, id);
