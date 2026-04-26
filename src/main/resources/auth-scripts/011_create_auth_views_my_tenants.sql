/* ============================================================
   AUTH · VIEWS
   ============================================================ */

/* ------------------------------------------------------------
   View: my_tenants
   ------------------------------------------------------------
   - Lista tenants onde o usuário é owner ou possui role
   - Marca se o usuário é owner do tenant
   ------------------------------------------------------------ */

CREATE OR REPLACE VIEW auth.my_tenants AS
SELECT DISTINCT
    t.uid  AS tenant_uid,
    t.name,
    t.type,
    (t.owner_uid = auth.fun_auth_user_id()) AS is_owner
FROM auth.tenants t
WHERE t.owner_uid = auth.fun_auth_user_id()

UNION

SELECT DISTINCT
    t.uid  AS tenant_uid,
    t.name,
    t.type,
    (t.owner_uid = auth.fun_auth_user_id()) AS is_owner
FROM auth.user_roles ur
         JOIN auth.tenants t
              ON t.uid = ur.tenant_id
WHERE ur.user_id = auth.fun_auth_user_id();


/* ------------------------------------------------------------
   View: role_permissions_effective
   ------------------------------------------------------------
   - Expõe permissões efetivas já resolvidas
   - Fonte: get_auth__effective_permissions()
   ------------------------------------------------------------ */

-- CREATE OR REPLACE VIEW auth.role_permissions_effective AS
-- SELECT
--     key   AS resource,
--     value AS permissions
-- FROM jsonb_each(auth.get_auth__effective_permissions());


/* ------------------------------------------------------------
   GRANTS
   ------------------------------------------------------------ */

-- REVOKE ALL ON auth.my_tenants FROM PUBLIC;
-- REVOKE ALL ON auth.role_permissions_effective FROM PUBLIC;
--
-- GRANT SELECT ON auth.my_tenants TO auth_user;
-- GRANT SELECT ON auth.role_permissions_effective TO auth_user;
