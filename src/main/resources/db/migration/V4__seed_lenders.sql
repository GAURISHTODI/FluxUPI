-- V4: three mock lenders, so a fresh database is immediately usable.
--
-- Reference data with fixed UUIDs, inserted idempotently. These are fixtures,
-- not real institutions — the names are invented.

INSERT INTO lenders (id, code, display_name, min_monthly_income, max_credit_limit,
                     income_multiple, max_exposure_multiple, annual_interest_rate_percent,
                     interest_strategy, default_tenure_months, active, created_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'QUICKCASH', 'QuickCash Finance (mock)',
     15000.00, 200000.00, 2.00, 4.00, 24.000, 'FLAT_RATE', 6, TRUE, now()),

    ('a0000000-0000-0000-0000-000000000002', 'PRUDENT', 'Prudent Credit Co. (mock)',
     30000.00, 500000.00, 3.00, 3.00, 16.000, 'REDUCING_BALANCE', 12, TRUE, now()),

    ('a0000000-0000-0000-0000-000000000003', 'STARTER', 'Starter Line (mock)',
     10000.00, 50000.00, 1.50, 2.50, 30.000, 'REDUCING_BALANCE', 3, TRUE, now())
ON CONFLICT (code) DO NOTHING;
