-- Acme Financial Group's database: the customer's data. AIsolate connects with the two roles of
-- 03_role.sql and owns none of it. Schema hr: the people, their pay, leave, objectives and the HR
-- documents; schema clients: the client accounts, their invoices and the client contracts.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA hr;

CREATE TABLE hr.employees (
    id          integer PRIMARY KEY,
    email       text    NOT NULL UNIQUE,
    full_name   text    NOT NULL,
    department  text    NOT NULL,
    title       text    NOT NULL,
    manager_id  integer REFERENCES hr.employees (id),
    start_date  date    NOT NULL
);

CREATE TABLE hr.compensation (
    employee_id    integer       PRIMARY KEY REFERENCES hr.employees (id),
    gross_salary   numeric(12,2) NOT NULL,
    currency       char(3)       NOT NULL DEFAULT 'EUR',
    target_bonus   numeric(12,2),
    iban           text          NOT NULL,
    internal_notes text
);

-- One row per employee and year; the days left are derived, never typed.
CREATE TABLE hr.leave (
    employee_id       integer NOT NULL REFERENCES hr.employees (id),
    year              integer NOT NULL,
    entitlement_days  integer NOT NULL,
    carried_over_days integer NOT NULL DEFAULT 0,
    taken_days        integer NOT NULL DEFAULT 0,
    planned_days      integer NOT NULL DEFAULT 0,
    remaining_days    integer GENERATED ALWAYS AS
                          (entitlement_days + carried_over_days - taken_days - planned_days) STORED,
    PRIMARY KEY (employee_id, year)
);

-- One row per objective; the manager's note is for the leadership, never for the employee.
CREATE TABLE hr.objectives (
    id           integer PRIMARY KEY,
    employee_id  integer NOT NULL REFERENCES hr.employees (id),
    period       text    NOT NULL,
    objective    text    NOT NULL,
    progress_pct integer NOT NULL CHECK (progress_pct BETWEEN 0 AND 100),
    status       text    NOT NULL CHECK (status IN ('on track', 'at risk', 'done')),
    manager_note text,
    updated_on   date    NOT NULL
);

-- The HR documents, one row per Markdown section, read by the context preparator
-- hr-documents. It reads them and writes nothing but the vectors (embedding IS NULL until
-- the first exchange embeds them). Bodies carry names, never e-mail addresses.
CREATE TABLE hr.documents (
    id                 bigserial PRIMARY KEY,
    context_preparator text NOT NULL,
    title              text NOT NULL,
    section            text NOT NULL,
    audience           text NOT NULL CHECK (audience IN ('all', 'hr', 'personal')),
    owner_email        text,
    body               text NOT NULL,
    embedding          vector
);

CREATE SCHEMA clients;

CREATE TABLE clients.accounts (
    id                 integer       PRIMARY KEY,
    name               text          NOT NULL UNIQUE,
    industry           text          NOT NULL,
    country            text          NOT NULL,
    product            text          NOT NULL,
    account_owner      text          NOT NULL,
    contract_value_eur numeric(12,2) NOT NULL,
    contract_start     date          NOT NULL,
    renewal_date       date          NOT NULL,
    status             text          NOT NULL CHECK (status IN ('active', 'renewing', 'at risk')),
    notes              text
);

-- An invoice is paid when paid_on is set, overdue when due_on has passed, open otherwise.
CREATE TABLE clients.invoices (
    id         integer       PRIMARY KEY,
    account_id integer       NOT NULL REFERENCES clients.accounts (id),
    number     text          NOT NULL UNIQUE,
    amount_eur numeric(12,2) NOT NULL,
    issued_on  date          NOT NULL,
    due_on     date          NOT NULL,
    paid_on    date
);

-- The client contracts and board memos, one row per section, read by the context preparator
-- client-documents; every row is the founders'.
CREATE TABLE clients.documents (
    id                 bigserial PRIMARY KEY,
    context_preparator text NOT NULL,
    title              text NOT NULL,
    section            text NOT NULL,
    body               text NOT NULL,
    embedding          vector
);
