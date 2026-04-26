/* ============================================================
   FUNCTION: auth.fun_auth_tenant_type_is
   ------------------------------------------------------------
   Verifica se o tenant_type presente no JWT
   é igual ao valor informado.
   Compatível com PostgREST e uso em RLS.
   ============================================================ */

-- DROP FUNCTION auth.fun_auth_tenant_type_is(text);

CREATE OR REPLACE FUNCTION auth.fun_auth_tenant_type_is(p_tenant_type text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $function$
SELECT
    current_setting('request.jwt.claim.tenant_type', true) = p_tenant_type;
$function$;

GRANT USAGE ON SCHEMA auth TO anon, auth_user;
GRANT EXECUTE ON FUNCTION auth.fun_auth_tenant_type_is(text) TO anon, auth_user;
