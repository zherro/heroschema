-- ========== SCHEMA ==========
GRANT USAGE ON SCHEMA auth TO authenticator, anon, auth_user;

-- ========== TABLES ==========
GRANT SELECT, INSERT, UPDATE, DELETE
      ON ALL TABLES IN SCHEMA auth
          TO authenticator;

-- ========== FUNCTIONS ==========
GRANT EXECUTE
ON ALL FUNCTIONS IN SCHEMA auth
TO authenticator;


-- ========== FUTURO (IMPORTANTE) ==========
ALTER DEFAULT PRIVILEGES IN SCHEMA auth
GRANT SELECT, INSERT, UPDATE, DELETE
      ON TABLES
          TO authenticator;

ALTER DEFAULT PRIVILEGES IN SCHEMA auth
GRANT EXECUTE
ON FUNCTIONS
TO authenticator;

ALTER DEFAULT PRIVILEGES IN SCHEMA auth
GRANT SELECT
      ON SEQUENCES
          TO authenticator;
