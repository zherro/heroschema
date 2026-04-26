/* ============================================================
   POSTGREST + RLS | BOOTSTRAP DE ROLES
   ------------------------------------------------------------
   Este script cria as roles necessárias para funcionamento
   correto do PostgREST com autenticação via JWT e Row Level
   Security (RLS).

   ✔ Seguro para rodar múltiplas vezes (idempotente)
   ✔ Compatível com PostgreSQL padrão
   ✔ Segue boas práticas de segurança

   Roles criadas:
   - anon        : usuário não autenticado
   - auth_user   : usuário autenticado via JWT
   - authenticator (opcional): role de conexão do PostgREST

   ============================================================ */


/* ============================================================
   1. CRIAÇÃO DAS ROLES LÓGICAS (SEM LOGIN)
   ------------------------------------------------------------
   Essas roles NÃO fazem login no banco.
   Elas são usadas apenas para:
   - GRANT de permissões
   - Políticas de Row Level Security (RLS)
   ============================================================ */

DO $$
BEGIN
    -- Role para usuários não autenticados (sem JWT)
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'anon'
    ) THEN
CREATE ROLE anon NOLOGIN;
END IF;

    -- Role para usuários autenticados (JWT válido)
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'auth_user'
    ) THEN
CREATE ROLE auth_user NOLOGIN;
END IF;
END$$;


/* ============================================================
   2. ROLE DE CONEXÃO DO POSTGREST (OPCIONAL)
   ------------------------------------------------------------
   ⚠️ SOMENTE DESCOMENTE SE:
   - Você controla o banco (Postgres local / VPS)
   - O PostgREST conecta diretamente no PostgreSQL

   ❌ NÃO USE SE:
   - Supabase
   - RDS gerenciado
   - Neon / Railway / serviços similares

   Esta role:
   - É usada APENAS para conexão
   - Não herda permissões automaticamente
   - Assume 'anon' ou 'auth_user' via SET ROLE
   ============================================================ */

DO
$$
BEGIN
   IF NOT EXISTS (
      SELECT 1
      FROM pg_roles
      WHERE rolname = 'authenticator'
   ) THEN
CREATE ROLE authenticator
    LOGIN
         NOINHERIT
         PASSWORD 'TROQUE_POR_UMA_SENHA_FORTE';
END IF;
END
$$;

-- para update
ALTER ROLE authenticator
  PASSWORD 'TROQUE_POR_UMA_SENHA_FORTE';


-- Concede permissão para assumir as roles lógicas
GRANT anon, auth_user TO authenticator;


/* ============================================================
   3. OBSERVAÇÕES IMPORTANTES DE SEGURANÇA
   ------------------------------------------------------------

   - Essas roles NÃO têm acesso a nada por padrão
   - Você DEVE:
     • Conceder GRANT mínimos em schemas/tabelas
     • Criar políticas de RLS explicitamente

   Exemplo (não incluído de propósito):
     GRANT USAGE ON SCHEMA public TO anon, auth_user;

   - Nunca use:
       SET row_security = off;
     em sessões da API

   ============================================================ */



-- PUBLIC
-- REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO anon, auth_user;

-- AUTH
CREATE SCHEMA IF NOT EXISTS auth;
-- REVOKE ALL ON SCHEMA auth FROM PUBLIC;
GRANT USAGE ON SCHEMA auth TO authenticator, anon, auth_user;

/* ============================================================
   FIM DO SCRIPT
   ============================================================ */

ALTER ROLE authenticator SET search_path = auth, public;
ALTER ROLE anon SET search_path = auth, public;
ALTER ROLE auth_user SET search_path = auth, public;
