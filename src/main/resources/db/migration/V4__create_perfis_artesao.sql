
CREATE TABLE perfis_artesao (
    id UUID PRIMARY KEY,

    conta_id UUID NOT NULL,

    slug VARCHAR(90) NOT NULL,

    nome VARCHAR(240) NOT NULL,
    atelie VARCHAR(240) NOT NULL,

    historia VARCHAR(2000) NOT NULL DEFAULT '',
    territorio VARCHAR(120) NOT NULL DEFAULT '',
    tecnica VARCHAR(120) NOT NULL DEFAULT '',

    imagem VARCHAR(1024) NOT NULL
    DEFAULT '/fotos/ImagemBase.webp',

    CONSTRAINT fk_perfis_artesao_conta
    FOREIGN KEY (conta_id)
    REFERENCES contas(id) ON DELETE CASCADE,

    CONSTRAINT uk_perfis_artesao_conta
    UNIQUE (conta_id),

    CONSTRAINT uk_perfis_artesao_slug
    UNIQUE (slug),

    CONSTRAINT ck_perfis_artesao_nome
    CHECK (nome = btrim(nome) AND nome <> ''),

    CONSTRAINT ck_perfis_artesao_atelie
    CHECK (atelie = btrim(atelie) AND atelie <> ''),

    CONSTRAINT ck_perfis_artesao_slug
    CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),

    CONSTRAINT ck_perfis_artesao_imagem
        CHECK (imagem <> '')
);
