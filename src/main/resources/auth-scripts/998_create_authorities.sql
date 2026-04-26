INSERT INTO auth.roles (id, uid, "name", created_at)
values
    (1, gen_random_uuid(), 'ROOT', now()),
    (2, gen_random_uuid(), 'ADMIN', now()),
    (3, gen_random_uuid(), 'USER', now());

