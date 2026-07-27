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

- `repository/MetadataRepository.kt` — the single source of truth for reading/writing schema metadata (information_schema / pg_catalog queries) and executing generated DDL. Almost everything funnels through here (`listSchemas`, `listTables`, `listColumns`, `getTableConstraints`, `listIndexes`, `listPolicies`, `executeTableSql`, grants, RLS, custom types, etc.). Read this file's function list before adding a new metadata query — there's likely something close already.
- `controller/SchemaController.kt` — by far the largest controller (~1000 lines). Owns the `/schemas` area: schema/table CRUD, the table-edit page (columns, PK/unique/FK, indexes, RLS policies, grants), and view editing. **This controller builds its own ALTER/CREATE/DROP SQL strings inline** (see `applyEdit`) rather than delegating to a service for most of it.
- `controller/TypeController.kt` — owns the `/types` area: list + create/edit/delete for custom ENUM and DOMAIN types (see "Custom types" below).
- `service/SchemaEditService.kt` — **dead code**. It duplicates `SchemaController.applyEdit`'s column/PK/unique/FK/index diffing logic but is never injected/called anywhere. If you're fixing a bug in table-edit form submission, the live code path is `SchemaController.applyEdit`, **not** this service.
- `service/AuthScriptsService.kt` — runs the versioned SQL scripts in `src/main/resources/auth-scripts/` (listed in `scripts.json`) against the target DB to install the multi-tenant auth schema (`auth.*` functions/tables used by generated RLS policies, e.g. `auth.fun_auth_current_tenant_id()`, `auth.fun_auth_user_id()`, `auth.fun_auth__has_permission()`). Tracks install status in an `auth_install_log` table it creates itself.
- `service/SqlTerminalService.kt` — backs the ad-hoc SQL terminal (`/sql-terminal`); restricts statements to SELECT/WITH/VALUES/INSERT/UPDATE/DELETE.
- `dtos/TableEditDtos.kt` — form-backing DTOs for the table-edit page. `TableEditFormDto.columns: MutableList<ColumnFormDto>` backs the dynamic column-rows table; `editIndexOriginalName`/`newIndexName`/`newIndexColumns`/`newIndexUnique`/`newIndexWhere` back the single shared "new/edit index" form fields.
- `dtos/SchemaDto.kt` — read-model DTOs (`ColumnDto`, `IndexDto`, `PolicyDto`, `TableGrantDto`, `FunctionDto`, `TableConstraintsDto`) returned by `MetadataRepository` for display.
- `dtos/TypeDtos.kt` — `EnumTypeDto`/`DomainTypeDto` (read models) and `EnumTypeFormDto`/`EnumTypeEditFormDto`/`DomainTypeFormDto`/`DomainTypeEditFormDto` (form-backing) for the `/types` pages.

**Templates & JS**: `templates/layout.html` is the shell every page renders into (`th:replace="~{${content}}"`), and it contains **one large inline `<script>` block used by every page** — dynamic column-row cloning (`#add-column-btn` / `#column-row-template`, plus the tenant/audit column preset button `#add-preset-columns-btn`), the index add/edit form wiring (`.index-edit-btn` click handler populates the shared `newIndexName`/`newIndexColumns`/`newIndexUnique`/`newIndexWhere`/`editIndexOriginalName` inputs), FK searchable selects (Select2, fetches `/schemas/fk/{schemas,tables,columns}`), the RLS policy composer auto-sync, and the SQL terminal's fetch-based execute/render logic. There are no separate `.js` files for shared behavior — when touching UI behavior for a page, check `layout.html` first, then the page's fragment under `templates/fragments/`. Some newer pages (`types-enum-create.html`, `types-domain-create.html`, `types-domain-edit.html`) instead colocate their own small `<script>` block directly in the fragment, since their JS (dynamic value rows, the CHECK-constraint builder) isn't needed on any other page.

**Index editing UI/backend flow**: the table-edit page has one shared form section for both "create index" and "edit index" (clicking an existing index's edit button just repopulates the same inputs and sets `editIndexOriginalName`, per `layout.html`). `SchemaController.applyEdit` reads `existingIndexes` and, when `editIndexOriginalName` is set and the original index isn't constraint-backed, issues a `DROP INDEX` before the `CREATE INDEX` for the new definition; this statement-building happens *before* the `statements.isEmpty()` "no changes" early-return, so an index-only submission (no column changes) isn't silently swallowed. (`SchemaEditService.buildAlterStatements`, dead code, has the same logic — it was the reference used when fixing this.)

**Auth/RLS SQL generation**: `SchemaController` has a family of `build*Sql` private methods (`buildPermissionsSql`, `buildPolicyByActionSql`, `buildComboPoliciesSql`) that generate GRANT/CREATE POLICY statements from `TablePermissionsFormDto`, mirrored by JS-side `buildSql()`/`resolveTemplate()` in `layout.html` for live preview before submit — keep both in sync if changing policy templates.

**Custom types (ENUM / DOMAIN)** — `/types`: `TypeController.kt` + `MetadataRepository` (`listEnumTypes`/`listDomainTypes` and the `createEnumType`/`addEnumValue`/`renameEnumValue`/`renameEnumType`/`dropEnumType` and `createDomainType`/`alterDomainDefault`/`alterDomainNotNull`/`alterDomainCheck`/`renameDomain`/`dropDomain` mutators) + `templates/fragments/types-*.html`.
  - Postgres has **no `DROP VALUE`** for enums (only add/rename), so the enum edit page intentionally has no "remove value" control — this is a Postgres limitation, not a missing feature.
  - A domain's base type can't be changed after creation (Postgres limitation); the edit page shows it read-only and only lets you drop+recreate.
  - `types-domain-create.html`/`types-domain-edit.html` have a **CHECK-constraint builder** (list-of-values / regex / range / custom, with live preview) instead of a raw expression box, so creating an "enum-like" domain (`CHECK (VALUE IN (...))`) doesn't require knowing Postgres CHECK syntax. The edit page additionally best-effort *parses* the domain's existing `checkExpr` (including Postgres's normalized `VALUE = ANY (ARRAY[...])` rewrite of an `IN (...)` list) to preselect the right builder mode; anything it can't recognize falls back to the "custom" raw-expression mode, which is always correct even when the friendly parse fails.
  - Custom enum/domain type names are merged into the table-edit page's column type `<select>`: `SchemaController`'s `typeOptions` (server-rendered rows) is exposed to JS as `window.COLUMN_TYPE_OPTIONS` (see `table-edit.html`), and `layout.html`'s `addColumnRow()` rebuilds the `.col-type-select` `<option>`s from that array so JS-added rows (new column / preset buttons) offer the same custom types as the server-rendered rows.
  - `MetadataRepository.listColumns` resolves the real type name (schema-qualified when the type lives outside the table's own schema) for enum/domain/composite columns instead of the information_schema literal `'USER-DEFINED'`, via `udt_schema`/`udt_name`.
  - **Gotcha**: a bare `? IS NULL OR col = ?` WHERE clause fails against Postgres with `could not determine data type of parameter $1` (it can't infer the parameter's type when compared only against `NULL`/itself with no other context) — `listEnumTypes`/`listDomainTypes` build the schema filter clause conditionally in Kotlin instead of relying on that pattern. Avoid `? IS NULL OR ...` in new nullable-filter queries against this DB.

## Known gotchas

- **Template edits not showing up when running via `bootRun`**: Thymeleaf/devtools reload templates from `build/resources/main`, not `src/main/resources` directly. If you edit a template mid-session and the running app still serves the old version, run `.\gradlew.bat processResources` (or just restart `bootRun`) to resync `build/resources/main` before devtools' auto-restart will pick it up.
