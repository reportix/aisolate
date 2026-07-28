-- The two credentials AIsolate holds for this database, one per airlock. Authorization is
-- decided by AIsolate's rules before any query runs; these roles are defence in depth: each
-- reads its own schema, time-boxed, and writes nothing but the vectors of its documents.
CREATE ROLE aisolate_hr LOGIN PASSWORD 'aisolate_hr';
GRANT USAGE ON SCHEMA hr TO aisolate_hr;
GRANT SELECT ON hr.employees, hr.compensation, hr.leave, hr.objectives, hr.documents TO aisolate_hr;
GRANT UPDATE (embedding) ON hr.documents TO aisolate_hr;
ALTER ROLE aisolate_hr SET statement_timeout = '5s';

CREATE ROLE aisolate_clients LOGIN PASSWORD 'aisolate_clients';
GRANT USAGE ON SCHEMA clients TO aisolate_clients;
GRANT SELECT ON clients.accounts, clients.invoices, clients.documents TO aisolate_clients;
GRANT UPDATE (embedding) ON clients.documents TO aisolate_clients;
ALTER ROLE aisolate_clients SET statement_timeout = '5s';
