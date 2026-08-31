-- V2: transactions and the double-entry ledger.
--
-- Two guarantees are pushed down into the database here rather than being left
-- to application code:
--
--   1. idempotency_key is UNIQUE. An application-level "does this key exist?"
--      check has a window between the SELECT and the INSERT that two concurrent
--      requests both fit through; a unique index does not.
--
--   2. A deferred constraint trigger asserts, at COMMIT time, that every
--      transaction's ledger entries balance. Even a hand-written UPDATE against
--      the database cannot leave the books lopsided.

CREATE TABLE transactions (
    id                  UUID           PRIMARY KEY,
    credit_line_id      UUID           NOT NULL REFERENCES credit_lines (id),
    type                VARCHAR(32)    NOT NULL,
    status              VARCHAR(32)    NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL,
    idempotency_key     VARCHAR(128)   NOT NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    payee_vpa           TEXT,
    description         TEXT,
    reversal_of_id      UUID           REFERENCES transactions (id),
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    completed_at        TIMESTAMPTZ,

    CONSTRAINT transactions_type_valid CHECK (type IN ('SPEND', 'REPAYMENT', 'REVERSAL')),
    CONSTRAINT transactions_status_valid CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REVERSED')),
    CONSTRAINT transactions_amount_positive CHECK (amount > 0),
    -- A REVERSAL must point at what it reverses; nothing else may.
    CONSTRAINT transactions_reversal_link_consistent CHECK (
        (type = 'REVERSAL' AND reversal_of_id IS NOT NULL)
        OR (type <> 'REVERSAL' AND reversal_of_id IS NULL)
    )
);

-- The constraint that makes idempotency real.
CREATE UNIQUE INDEX uq_transactions_idempotency_key ON transactions (idempotency_key);

-- A given spend may be reversed at most once.
CREATE UNIQUE INDEX uq_transactions_reversal_of ON transactions (reversal_of_id)
    WHERE reversal_of_id IS NOT NULL;

CREATE INDEX idx_transactions_credit_line ON transactions (credit_line_id, created_at);
CREATE INDEX idx_transactions_status ON transactions (status);

CREATE TABLE ledger_entries (
    id             UUID           PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id),
    credit_line_id UUID           NOT NULL REFERENCES credit_lines (id),
    account        VARCHAR(48)    NOT NULL,
    direction      VARCHAR(8)     NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    entry_seq      INTEGER        NOT NULL,
    narrative      TEXT,
    created_at     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ledger_entries_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_account_valid CHECK (account IN (
        'CUSTOMER_RECEIVABLE', 'LENDER_PAYABLE', 'MERCHANT_PAYABLE',
        'SETTLEMENT_CASH', 'INTEREST_INCOME', 'FEE_INCOME'
    )),
    -- Amounts are always positive; the sign lives in `direction`.
    CONSTRAINT ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_entries_seq_non_negative CHECK (entry_seq >= 0),
    CONSTRAINT uq_ledger_entries_txn_seq UNIQUE (transaction_id, entry_seq)
);

CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_credit_line ON ledger_entries (credit_line_id, created_at);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account);

-- The ledger invariant, enforced by Postgres.
--
-- Deferred to COMMIT because a journal entry is only balanced once *all* its
-- rows are in; checking after each INSERT would fail on the first line of every
-- entry ever written.
CREATE OR REPLACE FUNCTION assert_ledger_entry_balanced() RETURNS TRIGGER AS $$
DECLARE
    txn_id       UUID;
    debit_total  NUMERIC(19, 2);
    credit_total NUMERIC(19, 2);
    line_count   INTEGER;
BEGIN
    txn_id := COALESCE(NEW.transaction_id, OLD.transaction_id);

    SELECT COUNT(*),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO line_count, debit_total, credit_total
      FROM ledger_entries
     WHERE transaction_id = txn_id;

    -- Zero lines is the legitimate result of a rolled-back or fully removed
    -- entry; anything between 1 and 2 lines is a malformed journal entry.
    IF line_count = 0 THEN
        RETURN NULL;
    END IF;

    IF line_count < 2 THEN
        RAISE EXCEPTION 'Ledger entry for transaction % has only % line(s); a journal entry needs at least two',
            txn_id, line_count;
    END IF;

    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'Ledger imbalance for transaction %: debits % <> credits %',
            txn_id, debit_total, credit_total;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_must_balance
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_ledger_entry_balanced();
