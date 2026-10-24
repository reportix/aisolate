-- Roles, not users: who may ask an airlock is a set of the token's roles, no longer a list of e-mails.
-- A space's members become its developers (the e-mails of role developer, who edit it in the AIsolate UI)
-- and its roles; an airlock's users go and it gets roles of its own. No role is guessed from an e-mail:
-- every airlock admits nobody until the admin or a developer lists its roles (fail closed).
UPDATE spaces
SET definition = (definition - 'members') || jsonb_build_object(
    'developers', COALESCE(
        (SELECT jsonb_agg(m.member->>'email' ORDER BY m.n)
         FROM jsonb_array_elements(definition->'members') WITH ORDINALITY AS m(member, n)
         WHERE m.member->>'role' = 'developer'),
        '[]'::jsonb),
    'roles', '[]'::jsonb);

UPDATE airlocks SET definition = (definition - 'users') || '{"roles": []}'::jsonb;
