-- Test-only, after V2 (the shapes are V2's): the demo space and its airlock the backend's tests drive, with
-- the data sources' Secret names as a save in a cluster leaves them. The space's role ceo may ask the
-- airlock, as may its own role employee, from any client; jasmine develops the space, which defines the LLM
-- granite the airlock answers with. The airlock has no code yet: a deploy brings it. The product seeds
-- nothing.
INSERT INTO spaces (id, name, description, definition, created_by)
VALUES ('hr-acme', 'HR Acme', 'Acme people and compensation data for the HR assistant.', '{
  "developers": ["jasmine@example.com"],
  "roles": ["ceo"],
  "llms": [{"id": "granite", "provider": "ollama", "url": "http://host.minikube.internal:11434",
            "model": "granite4.1:8b"}],
  "data_sources": [
    {"id": "acme_hr", "url": "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
     "secret_ref": "hr-acme-ds-acme-hr-seed01"},
    {"id": "hr-documents", "url": "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
     "secret_ref": "hr-acme-ds-hr-documents-seed01"}
  ]
}'::jsonb, 'jasmine');

INSERT INTO airlocks (id, space_id, name, description, definition, created_by)
VALUES ('acme-hr', 'hr-acme', 'Acme HR assistant', 'Answers people, pay and HR document questions.', '{
  "roles": ["employee"],
  "callers": ["*"],
  "llm": "granite",
  "prompt": "You are the Acme HR assistant. Answer questions about employees and compensation using only the tools. All amounts are in EUR. Cite every figure with its evidence id in square brackets, for example [ev-1]. When a tool returns no rows, reply exactly: I don''t know.",
  "tools": [],
  "context_preparators": [],
  "memory_handlers": [],
  "verifiers": [],
  "data_sources": []
}'::jsonb, 'jasmine');
