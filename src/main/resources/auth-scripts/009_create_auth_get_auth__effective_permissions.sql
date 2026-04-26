/* ============================================================
   FUNCTION: auth.get_auth__effective_permissions
   ------------------------------------------------------------
   Retorna o mapa efetivo de permissoes do usuario autenticado
   no tenant atual no formato:
   {
     "resource": { "action": boolean }
   }
   Compatível com PostgREST e uso em RLS.
   ============================================================ */

-- DROP FUNCTION auth.get_auth__effective_permissions();

CREATE OR REPLACE FUNCTION auth.get_auth__effective_permissions()
RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
WITH ctx AS (
    SELECT
        auth.fun_auth_user_id()          AS uid,
        auth.fun_auth_current_tenant_id() AS tid
),
is_admin AS (
    SELECT EXISTS (
        SELECT 1
        FROM auth.user_roles ur, ctx
        WHERE ur.user_id = ctx.uid
          AND ur.tenant_id = ctx.tid
          AND ur.role_id = 2
    ) AS admin
),
src AS (
    -- Admin: overrides por tenant + defaults globais + grupos
    SELECT rp_t.resource, rp_t.permissions
    FROM auth.tenant_role_permissions rp_t, ctx, is_admin
    WHERE is_admin.admin = true
      AND rp_t.tenant_id = ctx.tid
      AND rp_t.role_id = 2

    UNION ALL

    SELECT rp_g.resource, rp_g.permissions
    FROM auth.role_permissions rp_g, is_admin
    WHERE is_admin.admin = true
      AND rp_g.role_id = 2

    UNION ALL

    -- Grupos (admin e nao-admin)
    SELECT gp.resource, gp.permissions
    FROM auth.user_groups ug
    JOIN auth.group_permissions gp
      ON gp.tenant_id = ug.tenant_id
     AND gp.group_id  = ug.group_id,
         ctx
    WHERE ug.user_id  = ctx.uid
      AND ug.tenant_id = ctx.tid
),
flat AS (
    SELECT
        resource,
        key AS action,
        (perm ->> key)::boolean AS allowed
    FROM src AS s(resource, perm),
         LATERAL jsonb_object_keys(perm) AS key
),
agg AS (
    SELECT
        resource,
        action,
        bool_or(allowed) AS allowed
    FROM flat
    GROUP BY resource, action
),
by_resource AS (
    SELECT
        resource,
        jsonb_object_agg(action, allowed) AS perms
    FROM agg
    GROUP BY resource
)
SELECT COALESCE(
               jsonb_object_agg(resource, perms),
               '{}'::jsonb
       )
FROM by_resource;
$function$;

GRANT USAGE ON SCHEMA auth TO anon, auth_user;
GRANT EXECUTE ON FUNCTION auth.get_auth__effective_permissions() TO anon, auth_user;
