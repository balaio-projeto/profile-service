CREATE TABLE solicitacoes_selo (
    id UUID PRIMARY KEY,
    perfil_id UUID NOT NULL REFERENCES perfis_artesao(id) ON DELETE CASCADE,
    mensagem VARCHAR(1000) NOT NULL,
    situacao VARCHAR(10) NOT NULL CHECK (situacao IN ('pendente', 'ajuste', 'aprovada')),
    solicitado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    motivo VARCHAR(1000),
    decidido_em TIMESTAMPTZ,
    decidido_por UUID REFERENCES contas(id),
    pecas_publicadas_decisao INTEGER CHECK (pecas_publicadas_decisao >= 0),
    CHECK ((situacao = 'pendente' AND decidido_em IS NULL AND decidido_por IS NULL AND motivo IS NULL)
        OR (situacao = 'aprovada' AND decidido_em IS NOT NULL AND decidido_por IS NOT NULL AND motivo IS NULL)
        OR (situacao = 'ajuste' AND decidido_em IS NOT NULL AND decidido_por IS NOT NULL AND motivo IS NOT NULL AND length(trim(motivo)) > 0))
);
CREATE UNIQUE INDEX solicitacoes_selo_pendente_unica ON solicitacoes_selo(perfil_id) WHERE situacao = 'pendente';
CREATE UNIQUE INDEX solicitacoes_selo_aprovada_unica ON solicitacoes_selo(perfil_id) WHERE situacao = 'aprovada';
CREATE INDEX solicitacoes_selo_historico ON solicitacoes_selo(perfil_id, solicitado_em DESC);
