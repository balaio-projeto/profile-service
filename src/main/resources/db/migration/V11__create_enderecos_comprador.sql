CREATE TABLE enderecos_comprador (
    id UUID PRIMARY KEY,
    conta_id UUID NOT NULL REFERENCES contas(id) ON DELETE CASCADE,
    apelido VARCHAR(80) NOT NULL,
    rua VARCHAR(255) NOT NULL,
    bairro VARCHAR(255) NOT NULL,
    cep VARCHAR(9) NOT NULL,
    principal BOOLEAN NOT NULL DEFAULT FALSE,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_endereco_cep CHECK (cep ~ '^[0-9]{5}-?[0-9]{3}$')
);

CREATE INDEX idx_enderecos_comprador_conta ON enderecos_comprador(conta_id);
CREATE UNIQUE INDEX uk_endereco_principal_por_conta ON enderecos_comprador(conta_id) WHERE principal;
