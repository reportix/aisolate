-- Five employees: Nora Varga (CEO) and Leo Brandt (co-founder) in Executive; Eric Lindholm, Hanna
-- Volkerts and Tomas Erlund in Engineering. Hanna and Tomas have no login: they are rows for the
-- figures the CEO aggregates. Dates are relative to today so the demo stays true over time.
INSERT INTO hr.employees (id, email, full_name, department, title, manager_id, start_date) VALUES
    (1, 'nora@example.com',  'Nora Varga',      'Executive',   'Chief Executive Officer', NULL, DATE '2019-01-15'),
    (2, 'eric@example.com',  'Eric Lindholm',   'Engineering', 'Software Engineer',       1,    DATE '2023-03-01'),
    (3, 'leo@example.com',   'Leo Brandt',      'Executive',   'Co-founder',              NULL, DATE '2019-01-15'),
    (4, 'hanna@example.com', 'Hanna Volkerts',  'Engineering', 'Senior Engineer',         1,    DATE '2022-05-02'),
    (5, 'tomas@example.com', 'Tomas Erlund',    'Engineering', 'Software Engineer',       1,    DATE '2024-09-16');

INSERT INTO hr.compensation (employee_id, gross_salary, currency, target_bonus, iban, internal_notes) VALUES
    (1, 235000.00, 'EUR', 55000.00, 'DE00 1000 0000 0000 0000 01', NULL),
    (2,  78000.00, 'EUR',  5000.00, 'DE00 1000 0000 0000 0000 02', 'Promotion review scheduled Q4 2026'),
    (3, 190000.00, 'EUR', 40000.00, 'DE00 1000 0000 0000 0000 03', NULL),
    (4,  96000.00, 'EUR',  8000.00, 'DE00 1000 0000 0000 0000 04', 'Retention bonus agreed for the risk engine release'),
    (5,  72000.00, 'EUR',  4000.00, 'DE00 1000 0000 0000 0000 05', NULL);

-- This year's leave: 30 days each, plus what carried over, minus what was taken and what is booked.
INSERT INTO hr.leave (employee_id, year, entitlement_days, carried_over_days, taken_days, planned_days)
SELECT employee_id, extract(year FROM current_date)::integer, 30, carried_over_days, taken_days, planned_days
FROM (VALUES
    (1, 0,  8, 10),
    (2, 4, 15,  5),
    (3, 2, 12,  0),
    (4, 5, 22,  3),
    (5, 0,  6,  8)
) AS v (employee_id, carried_over_days, taken_days, planned_days);

-- This year's objectives; four are at risk, one per person but the co-founder.
INSERT INTO hr.objectives (id, employee_id, period, objective, progress_pct, status, manager_note, updated_on)
SELECT id, employee_id, to_char(current_date, 'YYYY'), objective, progress_pct, status, manager_note, current_date - days_ago
FROM (VALUES
    ( 1, 2, 'Ship the mobile banking app 2.0',            70, 'on track', 'Strong ownership; candidate for tech lead',              6),
    ( 2, 2, 'Cut payment settlement time by 30 percent',  40, 'at risk',  'Blocked on the core banking team; escalate next month',  3),
    ( 3, 2, 'Mentor one junior engineer',                100, 'done',     NULL,                                                    20),
    ( 4, 1, 'Close three enterprise renewals',            66, 'on track', NULL,                                                     2),
    ( 5, 1, 'Hire two engineers',                         50, 'at risk',  NULL,                                                     9),
    ( 6, 3, 'Launch the treasury dashboard',              55, 'on track', NULL,                                                     4),
    ( 7, 3, 'Pass the SOC 2 audit',                       80, 'on track', NULL,                                                     1),
    ( 8, 4, 'Release the risk engine 3.0',                85, 'on track', NULL,                                                     5),
    ( 9, 4, 'Halve the CI pipeline time',                 30, 'at risk',  'Needs the build-infrastructure budget',                  7),
    (10, 5, 'Write the on-call runbooks',                 90, 'on track', NULL,                                                     2),
    (11, 5, 'Benchmark the pricing engine',               20, 'at risk',  NULL,                                                    12)
) AS v (id, employee_id, objective, progress_pct, status, manager_note, days_ago);

-- Eight client accounts; three renew within the next two months, five within 90 days.
INSERT INTO clients.accounts (id, name, industry, country, product, account_owner, contract_value_eur,
                              contract_start, renewal_date, status, notes)
SELECT id, name, industry, country, product, account_owner, contract_value_eur,
       current_date - started_days_ago, current_date + renews_in_days, status, notes
FROM (VALUES
    (1, 'Varnholt Logistik AG',      'Logistics',        'Germany',     'Cash management',           'Nora Varga', 240000.00, 1030,  66, 'renewing', 'Wants a multi-year term at the renewal'),
    (2, 'Quellmar Foods NV',         'Food',             'Netherlands', 'Corporate credit facility', 'Leo Brandt',  95000.00,  590, 125, 'active',   NULL),
    (3, 'Talvik Shipping AS',        'Shipping',         'Estonia',     'Trade finance',             'Nora Varga', 180000.00,  880,  35, 'at risk',  'Executive sponsor left; the new CFO is reviewing its banks'),
    (4, 'Orsenne Retail SA',         'Retail',           'Luxembourg',  'Asset management mandate',  'Leo Brandt',  60000.00,  400, 309, 'active',   NULL),
    (5, 'Brakmoor Energie AG',       'Energy',           'Germany',     'Corporate credit facility', 'Nora Varga', 110000.00, 1090,   5, 'renewing', NULL),
    (6, 'Alpenfirn Holding AG',      'Holding',          'Switzerland', 'Cash management',           'Leo Brandt', 150000.00,  940, 156, 'active',   NULL),
    (7, 'Larkspire Biotech plc',     'Biotech',          'Ireland',     'FX hedging',                'Nora Varga',  45000.00,  650,  80, 'active',   NULL),
    (8, 'Velmar Chemie GmbH',        'Chemicals',        'Germany',     'FX hedging',                'Leo Brandt',  70000.00,  755,  50, 'at risk',  'Two P1 incidents last quarter; credit note requested')
) AS v (id, name, industry, country, product, account_owner, contract_value_eur, started_days_ago, renews_in_days, status, notes);

-- Twelve invoices, due 30 days after issue: three overdue (Talvik 45,000, Brakmoor 27,500 and Velmar
-- 17,500 EUR), two open, the rest paid.
INSERT INTO clients.invoices (id, account_id, number, amount_eur, issued_on, due_on, paid_on)
SELECT id, account_id, number, amount_eur, current_date - issued_days_ago, current_date - issued_days_ago + 30,
       CASE WHEN paid_days_ago IS NULL THEN NULL ELSE current_date - paid_days_ago END
FROM (VALUES
    ( 1, 1, 'INV-012', 120000.00, 200, 175),
    ( 2, 1, 'INV-027', 120000.00,  20, NULL),
    ( 3, 2, 'INV-015',  95000.00, 180, 160),
    ( 4, 3, 'INV-019',  90000.00, 230, 210),
    ( 5, 3, 'INV-031',  45000.00,  71, NULL),
    ( 6, 4, 'INV-022',  60000.00,  90,  70),
    ( 7, 5, 'INV-009',  55000.00, 260, 240),
    ( 8, 5, 'INV-038',  27500.00,  56, NULL),
    ( 9, 6, 'INV-017', 150000.00, 170, 150),
    (10, 7, 'INV-025',  45000.00, 100,  85),
    (11, 8, 'INV-044',  17500.00,  55, NULL),
    (12, 8, 'INV-048',  17500.00,   0, NULL)
) AS v (id, account_id, number, amount_eur, issued_days_ago, paid_days_ago);
