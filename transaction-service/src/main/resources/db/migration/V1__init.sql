CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pix_key VARCHAR(140) NOT NULL UNIQUE,
    balance NUMERIC(19,4) NOT NULL CHECK (balance >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pix_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    end_to_end_id VARCHAR(35) NOT NULL UNIQUE,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    target_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    failure_reason TEXT
);

-- Índice para consultas de extrato por conta, ordenado por data
CREATE INDEX idx_tx_source_created ON pix_transactions(source_account_id, created_at DESC);
CREATE INDEX idx_tx_target_created ON pix_transactions(target_account_id, created_at DESC);
