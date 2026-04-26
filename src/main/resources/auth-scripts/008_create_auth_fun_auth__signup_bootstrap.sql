/* ============================================================
   FUNCTION: auth.fun_auth__signup_bootstrap
   ------------------------------------------------------------
   Cria um novo usuario, tenant inicial e associa o usuario
   como admin (role_id = 2) do tenant criado.
   Retorna os UUIDs do usuario e do tenant.
   ============================================================ */

-- DROP FUNCTION auth.fun_auth__signup_bootstrap(text, text);

CREATE OR REPLACE FUNCTION auth.fun_auth__signup_bootstrap(
    p_login    text,
    p_password text
)
RETURNS TABLE (
    user_uid   uuid,
    tenant_uid uuid
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $function$
DECLARE
    v_user_uid   uuid := gen_random_uuid();
    v_tenant_uid uuid := gen_random_uuid();
BEGIN
    -- Cria usuario (necessario antes do tenant por FK)
INSERT INTO auth.users (uid, login, password, is_active)
VALUES (
           v_user_uid,
           p_login,
           crypt(p_password, gen_salt('bf', 10)),
           true
       );

-- Cria tenant com o usuario como owner
INSERT INTO auth.tenants (uid, owner_uid, name, type)
VALUES (
           v_tenant_uid,
           v_user_uid,
           p_login,
           'USER'
       );

-- Concede role admin (role_id = 2) no tenant
INSERT INTO auth.user_roles (user_id, tenant_id, role_id)
VALUES (
           v_user_uid,
           v_tenant_uid,
           2
       );

RETURN QUERY
SELECT v_user_uid, v_tenant_uid;
END;
$function$;

GRANT EXECUTE ON FUNCTION auth.fun_auth__signup_bootstrap(text, text) TO anon, auth_user;
