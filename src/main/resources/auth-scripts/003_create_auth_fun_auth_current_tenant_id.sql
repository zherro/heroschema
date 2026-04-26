/* ============================================================
   FUNÇÃO: auth.fun_auth_current_tenant_id()
   ------------------------------------------------------------
   Retorna o tenant_id presente no JWT da requisição atual.

   - Compatível com PostgREST
   - Segura para uso em RLS
   - Retorna NULL se não existir tenant no token
   ============================================================ */

CREATE SCHEMA IF NOT EXISTS auth;

CREATE OR REPLACE FUNCTION auth.fun_auth_current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
SELECT
    nullif(
            current_setting('request.jwt.claim.tenant_id', true),
            ''
    )::uuid;
$$;

GRANT USAGE ON SCHEMA auth TO anon, auth_user;
GRANT EXECUTE ON FUNCTION auth.fun_auth_current_tenant_id() TO anon, auth_user;
