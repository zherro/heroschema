/* ============================================================
   FUNCTION: auth.fun_auth__has_permission
   ------------------------------------------------------------
   Verifica se o usuário autenticado possui permissão para
   executar uma ação em um recurso, considerando:
   - permissões no JWT (quando presentes)
   - roles no tenant
   - permissões por grupo
   - override de owner para RBAC
   Compatível com PostgREST e uso em RLS.
   ============================================================ */

-- DROP FUNCTION auth.fun_auth__has_permission(text, text);

CREATE OR REPLACE FUNCTION auth.fun_auth__has_permission(
    p_resource text,
    p_action   text
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
uid        uuid := auth.fun_auth_user_id();
    tid        uuid :=  auth.fun_auth_current_tenant_id();
    perm       boolean := false;
    jwt_perms  jsonb := nullif(current_setting('request.jwt.claims', true), '')::jsonb -> 'perms';
    jwt_val    text;
    is_admin   boolean := false;
BEGIN
    -- 0) Preferir permissões calculadas no JWT (atalho rápido)
    IF jwt_perms IS NOT NULL THEN
        jwt_val := (jwt_perms -> p_resource ->> p_action);
        IF jwt_val IS NOT NULL THEN
            RETURN COALESCE(jwt_val::boolean, false);
END IF;
END IF;

    -- Verifica se é admin no tenant atual (role_id = 2)
SELECT EXISTS (
    SELECT 1
    FROM auth.user_roles ur
    WHERE ur.user_id = uid
      AND ur.tenant_id = tid
      AND ur.role_id = 2
) INTO is_admin;

-- 1) Avaliação via banco
IF is_admin THEN
        -- Admin: role padrão + override por tenant + permissões de grupo
SELECT bool_or(val)
INTO perm
FROM (
         SELECT COALESCE((rp_t.permissions ->> p_action)::boolean, false) AS val
         FROM auth.tenant_role_permissions rp_t
         WHERE rp_t.tenant_id = tid
           AND rp_t.role_id = 2
           AND rp_t.resource = p_resource

         UNION ALL

         SELECT COALESCE((rp_g.permissions ->> p_action)::boolean, false) AS val
         FROM auth.role_permissions rp_g
         WHERE rp_g.role_id = 2
           AND rp_g.resource = p_resource

         UNION ALL

         SELECT COALESCE((gp.permissions ->> p_action)::boolean, false) AS val
         FROM auth.user_groups ug
                  JOIN auth.group_permissions gp
                       ON gp.tenant_id = ug.tenant_id
                           AND gp.group_id  = ug.group_id
                           AND gp.resource  = p_resource
         WHERE ug.user_id  = uid
           AND ug.tenant_id = tid
     ) s;
ELSE
        -- Não admin: apenas permissões de grupo
SELECT bool_or(
               COALESCE((gp.permissions ->> p_action)::boolean, false)
       )
INTO perm
FROM auth.user_groups ug
         JOIN auth.group_permissions gp
              ON gp.tenant_id = ug.tenant_id
                  AND gp.group_id  = ug.group_id
                  AND gp.resource  = p_resource
WHERE ug.user_id  = uid
  AND ug.tenant_id = tid;
END IF;

    -- 2) Override para owner em gestão de RBAC
    IF NOT COALESCE(perm, false)
       AND p_resource = 'rbac'
       AND (p_action = 'grant' OR p_action = 'configure')
    THEN
SELECT EXISTS (
    SELECT 1
    FROM tenants t
    WHERE t.uid = tid
      AND t.owner_uid = uid
)
INTO perm;
END IF;

RETURN COALESCE(perm, false);
END;
$function$;

GRANT USAGE ON SCHEMA auth TO anon, auth_user;
GRANT EXECUTE ON FUNCTION auth.fun_auth__has_permission(text, text) TO anon, auth_user;
