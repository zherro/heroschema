# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

heroSchema — a Kotlin + Spring Boot web tool for managing a PostgreSQL database's schema (DDL) through a browser UI: create/rename/drop schemas, tables, columns, indexes, constraints (PK/unique/FK), views, grants, RLS policies, functions, plus a raw SQL terminal. It also has an "Auth" module that installs a canned multi-tenant auth setup (schema, functions, tables, RLS helpers) into the *target* Postgres database via versioned SQL scripts.

There is no built-in login/auth for the tool itself — `SecurityConfig.kt`, `JwtAuthenticationFilter.kt`, `JwtService.kt`, `PermissionCheck.kt`, `ApiAuthController.kt`, and `AuthDtos.kt` all exist as **empty (0-byte) stub files**. Don't assume they contain anything — check before relying on them.

## Commands

```powershell
# Run the app (needs DB_URL, DB_USER, DB_PASSWORD env vars pointing at a running Postgres)
.\gradlew.bat bootRun

# Build
.\gradlew.bat build

# Run all tests
.\gradlew.bat test

# Run a single test class
.\gradlew.bat test --tests "com.br.bettersoft.heroschema.HeroshemaApplicationTests"
```

Required env vars (see [README.md](README.md)):
```
DB_URL=jdbc:postgresql://localhost:5432/some_db
DB_USER=some_user
DB_PASSWORD=some_password
```
App serves on port `8081` (`src/main/resources/application.yaml`).

The IntelliJ run config at `.idea/runConfigurations/HeroshemaApplication.xml` already sets these env vars for local dev.

## Architecture

**Stack**: Kotlin, Spring Boot (MVC + Thymeleaf server-rendered pages, no SPA/frontend build step), Spring Data JDBC (`JdbcTemplate`), PostgreSQL driver. No Spring Security is actually wired up.

**Layering**: `Controller` (Spring MVC, returns Thymeleaf view names) → `Repository`/`Service` (raw SQL against the *target* Postgres via `JdbcTemplate`) → Thymeleaf templates + one shared inline `<script>` block.

- `repository/MetadataRepository.kt` — the single source of truth for reading/writing schema metadata (information_schema / pg_catalog queries) and executing generated DDL. Almost everything funnels through here (`listSchemas`, `listTables`, `listColumns`, `getTableConstraints`, `listIndexes`, `listPolicies`, `executeTableSql`, grants, RLS, etc.). Read this file's function list before adding a new metadata query — there's likely something close already.
- `controller/SchemaController.kt` — by far the largest controller (~1000 lines). Owns the `/schemas` area: schema/table CRUD, the table-edit page (columns, PK/unique/FK, indexes, RLS policies, grants), and view editing. **This controller builds its own ALTER/CREATE/DROP SQL strings inline** (see `applyEdit`) rather than delegating to a service for most of it.
- `service/SchemaEditService.kt` — **dead code**. It duplicates `SchemaController.applyEdit`'s column/PK/unique/FK/index diffing logic (including proper handling of `editIndexOriginalName` — dropping the old index before recreating it) but is never injected/called anywhere. If you're fixing a bug in table-edit form submission, the live code path is `SchemaController.applyEdit`, **not** this service — check both when they look like they should behave the same, since they've already drifted apart (the controller's index-edit handling is less complete than the service's).
- `service/AuthScriptsService.kt` — runs the versioned SQL scripts in `src/main/resources/auth-scripts/` (listed in `scripts.json`) against the target DB to install the multi-tenant auth schema (`auth.*` functions/tables used by generated RLS policies, e.g. `auth.fun_auth_current_tenant_id()`, `auth.fun_auth_user_id()`, `auth.fun_auth__has_permission()`). Tracks install status in an `auth_install_log` table it creates itself.
- `service/SqlTerminalService.kt` — backs the ad-hoc SQL terminal (`/sql-terminal`); restricts statements to SELECT/WITH/VALUES/INSERT/UPDATE/DELETE.
- `dtos/TableEditDtos.kt` — form-backing DTOs for the table-edit page. `TableEditFormDto.columns: MutableList<ColumnFormDto>` backs the dynamic column-rows table; `editIndexOriginalName`/`newIndexName`/`newIndexColumns`/`newIndexUnique`/`newIndexWhere` back the single shared "new/edit index" form fields.
- `dtos/SchemaDto.kt` — read-model DTOs (`ColumnDto`, `IndexDto`, `PolicyDto`, `TableGrantDto`, `FunctionDto`, `TableConstraintsDto`) returned by `MetadataRepository` for display.

**Templates & JS**: `templates/layout.html` is the shell every page renders into (`th:replace="~{${content}}"`), and it contains **one large inline `<script>` block used by every page** — dynamic column-row cloning (`#add-column-btn` / `#column-row-template`), the index add/edit form wiring (`.index-edit-btn` click handler populates the shared `newIndexName`/`newIndexColumns`/`newIndexUnique`/`newIndexWhere`/`editIndexOriginalName` inputs), FK searchable selects (Select2, fetches `/schemas/fk/{schemas,tables,columns}`), the RLS policy composer auto-sync, and the SQL terminal's fetch-based execute/render logic. There are no separate `.js` files — when touching UI behavior for a page, check `layout.html` first, then the page's fragment under `templates/fragments/`.

**Index editing UI/backend flow**: the table-edit page has one shared form section for both "create index" and "edit index" (clicking an existing index's edit button just repopulates the same inputs and sets `editIndexOriginalName`, per `layout.html`). On submit, `SchemaEditService.buildAlterStatements` (unused) correctly drops the old index by `editIndexOriginalName` before adding the new one; `SchemaController.applyEdit` (the actual live path) does not read `editIndexOriginalName` at all — it only ever appends a `CREATE INDEX` statement, so editing silently fails to drop the old index and creating a second index with the same form re-submits collide.

**Auth/RLS SQL generation**: `SchemaController` has a family of `build*Sql` private methods (`buildPermissionsSql`, `buildPolicyByActionSql`, `buildComboPoliciesSql`) that generate GRANT/CREATE POLICY statements from `TablePermissionsFormDto`, mirrored by JS-side `buildSql()`/`resolveTemplate()` in `layout.html` for live preview before submit — keep both in sync if changing policy templates.
