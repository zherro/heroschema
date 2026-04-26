-- Cria schemas básicos de autenticação multi-tenant

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS public;

-- Extensions: required for this script
-- gen_random_uuid() comes from pgcrypto
-- Ensure target schema exists and install extension into it explicitly

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA auth;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;