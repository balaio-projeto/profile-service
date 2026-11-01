CREATE TABLE password_recovery (
    id UUID PRIMARY KEY,
    conta_id UUID NOT NULL REFERENCES contas(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_password_recovery_conta_created ON password_recovery(conta_id, created_at);
