/* =========================================================
   TABLE: users
   ========================================================= */

CREATE TABLE auth.users (
                            id bigserial,
                            uid uuid DEFAULT gen_random_uuid() NOT NULL,
                            login text NOT NULL,
                            password text NOT NULL,
                            is_active boolean DEFAULT true,
                            welcome boolean DEFAULT false,
                            created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                            deleted_at timestamp with time zone,
                            CONSTRAINT users_pkey PRIMARY KEY (uid),
                            CONSTRAINT users_id_unique UNIQUE (id),
                            CONSTRAINT users_login_unique UNIQUE (login)
);

ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON TABLE auth.users TO auth_user;



/* =========================================================
   TABLE: roles
   ========================================================= */

CREATE TABLE auth.roles (
                            id bigserial,
                            uid uuid DEFAULT gen_random_uuid(),
                            name text NOT NULL,
                            created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            CONSTRAINT roles_pkey PRIMARY KEY (id)
);

ALTER TABLE auth.roles ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON TABLE auth.roles TO auth_user;



/* =========================================================
   TABLE: role_permissions (GLOBAL DEFAULTS)
   Define permissões padrão por role e recurso.
   Válidas para todos os tenants, exceto quando sobrescritas.
   ========================================================= */

CREATE TABLE auth.role_permissions (
                                       role_id int8 NOT NULL,
                                       resource text NOT NULL,
                                       permissions jsonb DEFAULT '{}'::jsonb,
                                       created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       CONSTRAINT role_permissions_role_fkey
                                           FOREIGN KEY (role_id)
                                               REFERENCES auth.roles(id)
                                               ON DELETE RESTRICT
);

ALTER TABLE auth.role_permissions ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE auth.role_permissions TO auth_user;

CREATE POLICY role_permissions_select_policy ON auth.role_permissions FOR SELECT TO auth_user USING (true);
CREATE POLICY role_permissions_insert_policy ON auth.role_permissions FOR INSERT TO auth_user WITH CHECK (auth.fun_auth__has_permission('rbac'::text, 'configure'::text));
CREATE POLICY role_permissions_update_policy ON auth.role_permissions FOR UPDATE TO auth_user USING (auth.fun_auth__has_permission('rbac'::text, 'configure'::text));
CREATE POLICY role_permissions_delete_policy ON auth.role_permissions FOR DELETE TO auth_user USING (auth.fun_auth__has_permission('rbac'::text, 'configure'::text));


/* =========================================================
   TABLE: tenants
   ========================================================= */

CREATE TABLE auth.tenants (
                              id bigserial NOT NULL,
                              uid uuid DEFAULT gen_random_uuid() NOT NULL,
                              owner_uid uuid NOT NULL,
                              name text NOT NULL,
                              type varchar DEFAULT 'USER' NOT NULL,
                              created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              CONSTRAINT tenants_pkey PRIMARY KEY (uid),
                              CONSTRAINT tenants_id_unique UNIQUE (id),
                              CONSTRAINT tenants_owner_uid_fkey
                                  FOREIGN KEY (owner_uid)
                                      REFERENCES auth.users(uid)
                                      ON DELETE RESTRICT
);

GRANT SELECT ON TABLE auth.tenants TO auth_user;

ALTER TABLE auth.tenants ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON TABLE auth.tenants TO anon;
GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE auth.tenants TO auth_user;
GRANT USAGE, SELECT ON SEQUENCE auth.tenants_id_seq TO auth_user;

CREATE POLICY tenants_auth_user_policy ON auth.tenants FOR SELECT TO auth_user USING (owner_uid =  auth.fun_auth_user_id());
CREATE POLICY tenants_auth_user_policy_insert ON auth.tenants FOR INSERT TO auth_user WITH CHECK (owner_uid = auth.fun_auth_user_id());


/* =========================================================
   TABLE: user_roles
   ========================================================= */

CREATE TABLE auth.user_roles (
                                 user_id uuid NOT NULL,
                                 tenant_id uuid NOT NULL,
                                 role_id int8 NOT NULL,
                                 CONSTRAINT user_roles_unique UNIQUE (tenant_id, user_id, role_id),
                                 CONSTRAINT user_roles_user_fkey
                                     FOREIGN KEY (user_id)
                                         REFERENCES auth.users(uid)
                                         ON DELETE RESTRICT,
                                 CONSTRAINT user_roles_tenant_fkey
                                     FOREIGN KEY (tenant_id)
                                         REFERENCES auth.tenants(uid)
                                         ON DELETE RESTRICT,
                                 CONSTRAINT user_roles_role_fkey
                                     FOREIGN KEY (role_id)
                                         REFERENCES auth.roles(id)
                                         ON DELETE RESTRICT
);

ALTER TABLE auth.user_roles ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT,DELETE ON TABLE auth.user_roles TO auth_user;

CREATE POLICY user_roles_select_policy ON auth.user_roles FOR SELECT TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY user_roles_select_self_policy ON auth.user_roles FOR SELECT TO auth_user USING (user_id = auth.fun_auth_user_id());
CREATE POLICY user_roles_insert_policy ON auth.user_roles FOR INSERT TO auth_user WITH CHECK ((tenant_id = auth.fun_auth_current_tenant_id()) AND auth.fun_auth__has_permission('rbac'::text, 'grant'::text));
CREATE POLICY user_roles_delete_policy ON auth.user_roles FOR DELETE TO auth_user USING ((tenant_id = auth.fun_auth_current_tenant_id()) AND auth.fun_auth__has_permission('rbac'::text, 'grant'::text));


/* =========================================================
   TABLE: tenant_role_permissions (PER-TENANT OVERRIDES)
   Tem precedência sobre role_permissions
   ========================================================= */

CREATE TABLE auth.tenant_role_permissions (
                                              tenant_id uuid NOT NULL,
                                              role_id int8 NOT NULL,
                                              resource text NOT NULL,
                                              permissions jsonb DEFAULT '{}'::jsonb,
                                              CONSTRAINT tenant_role_permissions_pkey
                                                  PRIMARY KEY (tenant_id, role_id, resource),
                                              CONSTRAINT tenant_role_permissions_tenant_fkey
                                                  FOREIGN KEY (tenant_id)
                                                      REFERENCES auth.tenants(uid)
                                                      ON DELETE RESTRICT,
                                              CONSTRAINT tenant_role_permissions_role_fkey
                                                  FOREIGN KEY (role_id)
                                                      REFERENCES auth.roles(id)
                                                      ON DELETE RESTRICT
);



ALTER TABLE auth.tenant_role_permissions ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE auth.tenant_role_permissions TO auth_user;

-- Only allow viewing/managing overrides for the current tenant
CREATE POLICY tenant_role_permissions_policy ON auth.tenant_role_permissions FOR SELECT TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY tenant_role_permissions_policy_insert ON auth.tenant_role_permissions FOR INSERT TO auth_user WITH CHECK (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text));
CREATE POLICY tenant_role_permissions_policy_update ON auth.tenant_role_permissions FOR UPDATE TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text))  WITH CHECK (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY tenant_role_permissions_policy_delete ON auth.tenant_role_permissions FOR DELETE TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text));



/* =========================================================
   TABLE: groups (PER-TENANT GROUPS)
   ========================================================= */

CREATE TABLE auth.groups (
                             id bigserial,
                             tenant_id uuid NOT NULL,
                             name text NOT NULL,
                             description text,
                             created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             CONSTRAINT groups_pkey PRIMARY KEY (id),
                             CONSTRAINT groups_unique UNIQUE (tenant_id, name),
                             CONSTRAINT groups_tenant_fkey
                                 FOREIGN KEY (tenant_id)
                                     REFERENCES auth.tenants(uid)
                                     ON DELETE RESTRICT
);


/* =========================================================
   TABLE: group_permissions
   ========================================================= */

CREATE TABLE auth.group_permissions (
                                        tenant_id uuid NOT NULL,
                                        group_id int8 NOT NULL,
                                        resource text NOT NULL,
                                        permissions jsonb DEFAULT '{}'::jsonb,
                                        CONSTRAINT group_permissions_unique UNIQUE (tenant_id, group_id, resource),
                                        CONSTRAINT group_permissions_tenant_fkey
                                            FOREIGN KEY (tenant_id)
                                                REFERENCES auth.tenants(uid)
                                                ON DELETE RESTRICT,
                                        CONSTRAINT group_permissions_group_fkey
                                            FOREIGN KEY (group_id)
                                                REFERENCES auth.groups(id)
                                                ON DELETE RESTRICT
);


ALTER TABLE auth.group_permissions ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE auth.group_permissions TO auth_user;

CREATE POLICY group_permissions_select_policy ON auth.group_permissions FOR SELECT TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY group_permissions_insert_policy ON auth.group_permissions FOR INSERT TO auth_user WITH CHECK (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text));
CREATE POLICY group_permissions_update_policy ON auth.group_permissions FOR UPDATE TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text)) WITH CHECK (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY group_permissions_delete_policy ON auth.group_permissions FOR DELETE TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'configure'::text));


/* =========================================================
   TABLE: user_groups (GROUP MEMBERSHIP)
   ========================================================= */

CREATE TABLE auth.user_groups (
                                  tenant_id uuid NOT NULL,
                                  user_id uuid NOT NULL,
                                  group_id bigint NOT NULL,
                                  CONSTRAINT user_groups_unique UNIQUE (tenant_id, user_id, group_id),
                                  CONSTRAINT user_groups_tenant_fkey
                                      FOREIGN KEY (tenant_id)
                                          REFERENCES auth.tenants(uid)
                                          ON DELETE RESTRICT,
                                  CONSTRAINT user_groups_user_fkey
                                      FOREIGN KEY (user_id)
                                          REFERENCES auth.users(uid)
                                          ON DELETE RESTRICT,
                                  CONSTRAINT user_groups_group_fkey
                                      FOREIGN KEY (group_id)
                                          REFERENCES auth.groups(id)
                                          ON DELETE RESTRICT
);


ALTER TABLE auth.user_groups ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT,DELETE ON TABLE auth.user_groups TO auth_user;

CREATE POLICY user_groups_select_prolicy ON auth.user_groups FOR SELECT TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id());
CREATE POLICY user_groups_insert_policy ON auth.user_groups FOR INSERT TO auth_user WITH CHECK (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'grant'::text));
CREATE POLICY user_groups_delete_policy ON auth.user_groups FOR DELETE TO auth_user USING (tenant_id = auth.fun_auth_current_tenant_id() AND auth.fun_auth__has_permission('rbac'::text, 'grant'::text));

