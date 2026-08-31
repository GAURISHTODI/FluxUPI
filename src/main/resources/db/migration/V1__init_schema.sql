-- V1: users, lenders and credit lines.
--
-- Schema is owned entirely by Flyway; Hibernate runs with ddl-auto=validate and
-- will refuse to start if a mapping and this file disagree.
--
-- Every money column is NUMERIC(19,2) — never float/double. Constraints that
-- protect money (non-negative balances, available <= approved) are declared
-- here as well as in the domain model, so a bad write cannot land even if it
-- bypasses the application.

CREATE TABLE users (
    id                      UUID           PRIMARY KEY,
    full_name               TEXT           NOT NULL,
    vpa                     TEXT           NOT NULL UNIQUE,
    declared_monthly_income NUMERIC(19, 2) NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL,

    CONSTRAINT users_income_non_negative CHECK (declared_monthly_income >= 0)
);

COMMENT ON COLUMN users.vpa IS 'Mock UPI handle. Never resolved against a real payment rail.';

CREATE TABLE lenders (
    id                           UUID           PRIMARY KEY,
    code                         TEXT           NOT NULL UNIQUE,
    display_name                 TEXT           NOT NULL,
    min_monthly_income           NUMERIC(19, 2) NOT NULL,
    max_credit_limit             NUMERIC(19, 2) NOT NULL,
    income_multiple              NUMERIC(6, 2)  NOT NULL,
    max_exposure_multiple        NUMERIC(6, 2)  NOT NULL,
    annual_interest_rate_percent NUMERIC(6, 3)  NOT NULL,
    interest_strategy            VARCHAR(32)    NOT NULL,
    default_tenure_months        INTEGER        NOT NULL,
    active                       BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at                   TIMESTAMPTZ    NOT NULL,

    CONSTRAINT lenders_interest_strategy_valid
        CHECK (interest_strategy IN ('FLAT_RATE', 'REDUCING_BALANCE')),
    CONSTRAINT lenders_tenure_positive CHECK (default_tenure_months >= 1),
    CONSTRAINT lenders_limits_non_negative
        CHECK (min_monthly_income >= 0 AND max_credit_limit >= 0),
    CONSTRAINT lenders_multiples_positive
        CHECK (income_multiple > 0 AND max_exposure_multiple > 0),
    CONSTRAINT lenders_rate_non_negative CHECK (annual_interest_rate_percent >= 0)
);

CREATE TABLE credit_lines (
    id                           UUID           PRIMARY KEY,
    user_id                      UUID           NOT NULL REFERENCES users (id),
    lender_id                    UUID           NOT NULL REFERENCES lenders (id),
    status                       VARCHAR(32)    NOT NULL,
    approved_limit               NUMERIC(19, 2) NOT NULL DEFAULT 0,
    available_limit              NUMERIC(19, 2) NOT NULL DEFAULT 0,
    annual_interest_rate_percent NUMERIC(6, 3)  NOT NULL,
    interest_strategy            VARCHAR(32)    NOT NULL,
    tenure_months                INTEGER        NOT NULL,
    decision_reason              TEXT,
    approved_at                  TIMESTAMPTZ,
    activated_at                 TIMESTAMPTZ,
    closed_at                    TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ    NOT NULL,
    updated_at                   TIMESTAMPTZ    NOT NULL,
    version                      BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT credit_lines_status_valid
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'ACTIVE', 'FROZEN', 'CLOSED', 'DEFAULTED')),
    CONSTRAINT credit_lines_interest_strategy_valid
        CHECK (interest_strategy IN ('FLAT_RATE', 'REDUCING_BALANCE')),
    CONSTRAINT credit_lines_tenure_positive CHECK (tenure_months >= 1),
    -- The balance invariant, enforced by the database itself: you can never
    -- spend past zero, and available headroom can never exceed the sanction.
    CONSTRAINT credit_lines_available_within_approved
        CHECK (available_limit >= 0 AND available_limit <= approved_limit),
    CONSTRAINT credit_lines_approved_non_negative CHECK (approved_limit >= 0)
);

CREATE INDEX idx_credit_lines_user ON credit_lines (user_id);
CREATE INDEX idx_credit_lines_lender ON credit_lines (lender_id);
CREATE INDEX idx_credit_lines_status ON credit_lines (status);
