/* ============================================================
   AUTH · LOGIN FUNCTIONS
   Schema: auth
   ============================================================ */

/* ------------------------------------------------------------
   Function: fun_auth__login_verify
   ------------------------------------------------------------
   - Valida login + senha
   - Retorna user_uid, tenant_uid e tenant_type
   ------------------------------------------------------------ */

CREATE OR REPLACE FUNCTION auth.fun_auth__login_verify(
  p_login text,
  p_password text
)
RETURNS TABLE (
  user_uid uuid,
  tenant_uid uuid,
  tenant_type text
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_uid uuid;
  v_hash text;
  v_tenant uuid;
  v_tenant_type text;
BEGIN
  SELECT u.uid, u.password
    INTO v_uid, v_hash
  FROM auth.users u
  WHERE u.login = p_login;

  IF v_uid IS NULL THEN
    RETURN;
  END IF;

  IF crypt(p_password, v_hash) <> v_hash THEN
    RETURN;
  END IF;

  SELECT ur.tenant_id
    INTO v_tenant
  FROM auth.user_roles ur
  WHERE ur.user_id = v_uid
  LIMIT 1;

  SELECT t.type
    INTO v_tenant_type
  FROM auth.tenants t
  WHERE t.uid = v_tenant
  LIMIT 1;

  RETURN QUERY
  SELECT v_uid, v_tenant, v_tenant_type;
END;
$$;


/* ------------------------------------------------------------
   Function: fun_auth__login_with_perms
   ------------------------------------------------------------
   - Executa login
   - Injeta request.jwt.claims
   - Calcula permissões efetivas
   - Retorna payload pronto pra JWT / API
   ------------------------------------------------------------ */

CREATE OR REPLACE FUNCTION auth.fun_auth__login_with_perms(
  p_login text,
  p_password text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_user uuid;
  v_tenant uuid;
  v_tenant_type text;
  v_perms jsonb;
  v_claims text;
BEGIN
  SELECT user_uid, tenant_uid, tenant_type
    INTO v_user, v_tenant, v_tenant_type
  FROM auth.fun_auth__login_verify(p_login, p_password)
  LIMIT 1;

  IF v_user IS NULL THEN
    RETURN NULL;
  END IF;

  v_claims :=
    jsonb_build_object(
      'user_id', v_user::text,
      'tenant_id', v_tenant::text,
      'tenant_type', v_tenant_type
    )::text;

  PERFORM set_config('request.jwt.claims', v_claims, true);

  SELECT auth.get_auth__effective_permissions()
    INTO v_perms;

  RETURN jsonb_build_object(
    'user_uid', v_user::text,
    'tenant_uid', v_tenant::text,
    'tenant_type', v_tenant_type,
    'perms', COALESCE(v_perms, '{}'::jsonb)
  );
END;
$$;


/* ------------------------------------------------------------
   GRANTS
   ------------------------------------------------------------ */

REVOKE ALL ON FUNCTION auth.fun_auth__login_verify(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.fun_auth__login_with_perms(text, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION auth.fun_auth__login_verify(text, text) TO anon;
GRANT EXECUTE ON FUNCTION auth.fun_auth__login_with_perms(text, text) TO anon;

GRANT EXECUTE ON FUNCTION auth.fun_auth__login_verify(text, text) TO auth_user;
GRANT EXECUTE ON FUNCTION auth.fun_auth__login_with_perms(text, text) TO auth_user;
