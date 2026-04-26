/* ============================================================
   FUNCTION: auth.fun_auth_user_id
   ------------------------------------------------------------
   Retorna o user_id presente no JWT da sessão atual.
   Compatível com PostgREST e uso em RLS.
   ============================================================ */

-- DROP FUNCTION auth.fun_auth_user_id();

CREATE OR REPLACE FUNCTION auth.fun_auth_user_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $function$
SELECT
    nullif(
            current_setting('request.jwt.claim.user_id', true),
            ''
    )::uuid;
$function$;

GRANT USAGE ON SCHEMA auth TO anon, auth_user;
GRANT EXECUTE ON FUNCTION auth.fun_auth_user_id() TO anon, auth_user;
