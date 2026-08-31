-- V3: repayment schedules and their instalments.
--
-- A schedule is an immutable snapshot of what the borrower was told at
-- generation time. Borrowing more supersedes the old schedule and creates a
-- new one rather than editing rows, so the columns here are mostly immutable.

CREATE TABLE repayment_schedules (
    id                           UUID           PRIMARY KEY,
    credit_line_id               UUID           NOT NULL REFERENCES credit_lines (id),
    status                       VARCHAR(16)    NOT NULL,
    interest_strategy            VARCHAR(32)    NOT NULL,
    principal                    NUMERIC(19, 2) NOT NULL,
    total_interest               NUMERIC(19, 2) NOT NULL,
    annual_interest_rate_percent NUMERIC(6, 3)  NOT NULL,
    generated_at                 TIMESTAMPTZ    NOT NULL,

    CONSTRAINT repayment_schedules_status_valid
        CHECK (status IN ('ACTIVE', 'DELINQUENT', 'SETTLED', 'SUPERSEDED')),
    CONSTRAINT repayment_schedules_strategy_valid
        CHECK (interest_strategy IN ('FLAT_RATE', 'REDUCING_BALANCE')),
    CONSTRAINT repayment_schedules_amounts_non_negative
        CHECK (principal > 0 AND total_interest >= 0)
);

-- At most one schedule per credit line may be live at a time.
CREATE UNIQUE INDEX uq_repayment_schedules_one_active_per_line
    ON repayment_schedules (credit_line_id)
    WHERE status IN ('ACTIVE', 'DELINQUENT');

CREATE INDEX idx_repayment_schedules_credit_line ON repayment_schedules (credit_line_id, generated_at);

CREATE TABLE installments (
    id                  UUID           PRIMARY KEY,
    schedule_id         UUID           NOT NULL REFERENCES repayment_schedules (id),
    installment_number  INTEGER        NOT NULL,
    due_date            DATE           NOT NULL,
    principal_component NUMERIC(19, 2) NOT NULL,
    interest_component  NUMERIC(19, 2) NOT NULL,
    paid_amount         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status              VARCHAR(16)    NOT NULL,
    paid_at             TIMESTAMPTZ,

    CONSTRAINT installments_status_valid
        CHECK (status IN ('UPCOMING', 'DUE', 'OVERDUE', 'PAID')),
    CONSTRAINT installments_number_positive CHECK (installment_number >= 1),
    CONSTRAINT installments_components_non_negative
        CHECK (principal_component >= 0 AND interest_component >= 0),
    CONSTRAINT installments_paid_non_negative CHECK (paid_amount >= 0),
    CONSTRAINT uq_installments_schedule_number UNIQUE (schedule_id, installment_number)
);

CREATE INDEX idx_installments_schedule ON installments (schedule_id, installment_number);
CREATE INDEX idx_installments_due ON installments (due_date) WHERE status <> 'PAID';
