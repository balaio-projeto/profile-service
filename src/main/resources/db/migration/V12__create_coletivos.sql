CREATE TABLE coletivos (
    id UUID PRIMARY KEY,
    slug VARCHAR(90) NOT NULL UNIQUE,
    nome VARCHAR(240) NOT NULL,
    territorio VARCHAR(120) NOT NULL DEFAULT '',
    fundado VARCHAR(40) NOT NULL DEFAULT '',
    historia VARCHAR(2000) NOT NULL DEFAULT '',
    imagem VARCHAR(1024) NOT NULL DEFAULT '/fotos/ImagemBase.webp',
    apoio TEXT[] NOT NULL DEFAULT '{}',
    CONSTRAINT ck_coletivos_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE coletivo_membros (
    coletivo_id UUID NOT NULL REFERENCES coletivos(id) ON DELETE CASCADE,
    perfil_id UUID NOT NULL REFERENCES perfis_artesao(id) ON DELETE CASCADE,
    PRIMARY KEY (coletivo_id, perfil_id)
);

CREATE INDEX idx_coletivo_membros_perfil ON coletivo_membros(perfil_id);
