package com.br.bettersoft.heroschema.repository

import com.br.bettersoft.heroschema.dtos.ColumnDto
import com.br.bettersoft.heroschema.dtos.DomainTypeDto
import com.br.bettersoft.heroschema.dtos.EnumTypeDto
import com.br.bettersoft.heroschema.dtos.FunctionDto
import com.br.bettersoft.heroschema.dtos.ForeignKeyInfoDto
import com.br.bettersoft.heroschema.dtos.TableConstraintsDto
import com.br.bettersoft.heroschema.dtos.IndexDto
import com.br.bettersoft.heroschema.dtos.PolicyDto
import com.br.bettersoft.heroschema.dtos.SqlStatementResultDto
import com.br.bettersoft.heroschema.dtos.TableGrantDto
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Types

@Repository
class MetadataRepository(
    private val jdbc: JdbcTemplate
) {

    private val logger = LoggerFactory.getLogger(MetadataRepository::class.java)

    fun listSchemas(): List<String> =
        jdbc.queryForList(
            """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('pg_catalog', 'information_schema')
            ORDER BY schema_name
            """.trimIndent(),
            String::class.java
        )

    fun listTables(schema: String): List<String> =
        jdbc.queryForList(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(),
            String::class.java,
            schema
        )

    fun listViews(schema: String): List<String> =
        jdbc.queryForList(
            """
            SELECT table_name
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent(),
            String::class.java,
            schema
        )

    fun getViewDdl(schema: String, view: String): String {
        val def = jdbc.queryForObject(
            """
            SELECT 'CREATE OR REPLACE VIEW "' || schemaname || '"."' || viewname || '" AS' || chr(10) || definition
            FROM pg_views
            WHERE schemaname = ?
              AND viewname = ?
            """.trimIndent(),
            String::class.java,
            schema,
            view
        )
        return def ?: "-- Could not retrieve DDL for $schema.$view"
    }

    fun listViewGrants(schema: String, view: String): List<TableGrantDto> =
        jdbc.query(
            """
            SELECT
              grantee,
              string_agg(DISTINCT privilege_type, ',' ORDER BY privilege_type) AS privileges
            FROM information_schema.role_table_grants
            WHERE table_schema = ?
              AND table_name = ?
            GROUP BY grantee
            ORDER BY grantee
            """.trimIndent(),
            { rs, _ ->
                TableGrantDto(
                    grantee = rs.getString("grantee"),
                    privileges = rs.getString("privileges") ?: ""
                )
            },
            schema,
            view
        )

    fun grantViewPrivileges(schema: String, view: String, grantee: String, privileges: List<String>) {
        if (privileges.isEmpty()) return
        val sql = "GRANT ${privileges.joinToString(",")} ON TABLE \"$schema\".\"$view\" TO \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun revokeAllViewPrivileges(schema: String, view: String, grantee: String) {
        val sql = "REVOKE ALL PRIVILEGES ON TABLE \"$schema\".\"$view\" FROM \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun listViewPolicies(schema: String, view: String): List<PolicyDto> =
        jdbc.query(
            """
            SELECT policyname, cmd, roles, qual, with_check
            FROM pg_policies
            WHERE schemaname = ?
              AND tablename = ?
            ORDER BY policyname
            """.trimIndent(),
            { rs, _ ->
                val policyName = rs.getString("policyname")
                val cmd = rs.getString("cmd") ?: "ALL"
                val rolesArr = (rs.getArray("roles")?.array as? Array<*>)
                val roles = rolesArr
                    ?.mapNotNull { it?.toString() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?: "public"
                val usingExpr = rs.getString("qual")
                val withCheckExpr = rs.getString("with_check")
                val definitionSql = buildPolicyDefinitionSql(
                    schema = schema,
                    table = view,
                    policyName = policyName,
                    command = cmd,
                    roles = roles,
                    usingExpr = usingExpr,
                    withCheckExpr = withCheckExpr
                )
                PolicyDto(
                    name = policyName,
                    command = cmd,
                    roles = roles,
                    usingExpr = usingExpr,
                    withCheckExpr = withCheckExpr,
                    definitionSql = definitionSql
                )
            },
            schema,
            view
        )

    fun listRoles(): List<String> =
        jdbc.queryForList(
            """
            SELECT rolname
            FROM pg_roles
            WHERE rolname NOT LIKE 'pg_%'
            ORDER BY rolname
            """.trimIndent(),
            String::class.java
        )

    fun listSqlColumns(schema: String): List<String> =
        jdbc.queryForList(
            """
            SELECT DISTINCT c.column_name
            FROM information_schema.columns c
            WHERE c.table_schema = ?
            ORDER BY c.column_name
            """.trimIndent(),
            String::class.java,
            schema
        )

    fun listSqlFunctions(schema: String): List<String> =
        jdbc.queryForList(
            """
            SELECT p.proname
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ?
            ORDER BY p.proname
            """.trimIndent(),
            String::class.java,
            schema
        )

    fun executeSqlStatements(schema: String, statements: List<String>, maxRows: Int): List<SqlStatementResultDto> {
        val safeMaxRows = maxRows.coerceIn(1, 2000)

        val ds = jdbc.dataSource ?: error("DataSource not configured")
        ds.connection.use { conn ->
            conn.createStatement().use { setup ->
                setup.execute("SET search_path TO \"$schema\", public")
            }

            return statements.map { statement ->
                val startedAt = System.currentTimeMillis()

                conn.createStatement().use { stmt ->
                    stmt.maxRows = safeMaxRows
                    val hasResult = stmt.execute(statement)
                    val elapsed = System.currentTimeMillis() - startedAt

                    if (hasResult) {
                        stmt.resultSet.use { rs ->
                            val meta = rs.metaData
                            val columns = (1..meta.columnCount).map { idx ->
                                meta.getColumnLabel(idx) ?: meta.getColumnName(idx)
                            }

                            val rows = mutableListOf<List<String?>>()
                            while (rs.next()) {
                                val row = (1..meta.columnCount).map { idx ->
                                    if (meta.getColumnType(idx) == Types.BINARY ||
                                        meta.getColumnType(idx) == Types.VARBINARY ||
                                        meta.getColumnType(idx) == Types.LONGVARBINARY
                                    ) {
                                        "<binary>"
                                    } else {
                                        rs.getObject(idx)?.toString()
                                    }
                                }
                                rows.add(row)
                            }

                            SqlStatementResultDto(
                                statement = statement,
                                kind = "result_set",
                                rowCount = rows.size,
                                columns = columns,
                                rows = rows,
                                elapsedMs = elapsed
                            )
                        }
                    } else {
                        SqlStatementResultDto(
                            statement = statement,
                            kind = "update_count",
                            rowCount = stmt.updateCount,
                            message = "Statement executed",
                            elapsedMs = elapsed
                        )
                    }
                }
            }
        }
    }

    fun renameSchema(oldName: String, newName: String) {
        val sql = "ALTER SCHEMA \"$oldName\" RENAME TO \"$newName\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun dropSchema(name: String, cascade: Boolean) {
        val optCascade = if (cascade) " CASCADE" else " RESTRICT"
        val sql = "DROP SCHEMA IF EXISTS \"$name\"$optCascade"
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun createSchema(name: String) {
        val sql = "CREATE SCHEMA IF NOT EXISTS \"$name\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun listColumns(schema: String, table: String): List<ColumnDto> =
        jdbc.query(
            """
            SELECT
                c.column_name,
                c.data_type,
                c.udt_schema,
                c.udt_name,
                c.is_nullable,
                regexp_replace(
                    c.column_default,
                    '(^|[^.])(fun_auth_[a-zA-Z0-9_]*)\s*\(',
                    E'\\1auth.\\2(',
                    'g'
                ) AS column_default,
                pgd.description AS column_comment
            FROM information_schema.columns c
            LEFT JOIN pg_catalog.pg_class pc
                ON pc.relname = c.table_name
            LEFT JOIN pg_catalog.pg_namespace pn
                ON pn.oid = pc.relnamespace
            LEFT JOIN pg_catalog.pg_attribute pa
                ON pa.attrelid = pc.oid
               AND pa.attname = c.column_name
            LEFT JOIN pg_catalog.pg_description pgd
                ON pgd.objoid = pa.attrelid
               AND pgd.objsubid = pa.attnum
            WHERE c.table_schema = ?
              AND c.table_name = ?
              AND pn.nspname = c.table_schema
            ORDER BY c.ordinal_position
            """.trimIndent(),
            { rs, _ ->
                val dataType = rs.getString("data_type")
                val udtSchema = rs.getString("udt_schema")
                val udtName = rs.getString("udt_name")
                // information_schema reports enum/domain/composite columns as 'USER-DEFINED';
                // resolve the real (schema-qualified when foreign) type name from udt_schema/udt_name instead.
                val resolvedType = if (dataType == "USER-DEFINED") {
                    if (udtSchema == schema) udtName else "$udtSchema.$udtName"
                } else {
                    dataType
                }

                ColumnDto(
                    name = rs.getString("column_name"),
                    type = resolvedType,
                    nullable = rs.getString("is_nullable") == "YES",
                    defaultValue = rs.getString("column_default"),
                    comment = rs.getString("column_comment")
                )
            },
            schema,
            table
        )

    fun listFunctions(schema: String?, search: String?): List<FunctionDto> {
        val all = jdbc.query(
            """
            SELECT
              n.nspname      AS schema_name,
              p.proname      AS function_name,
              pg_get_function_arguments(p.oid)     AS arguments,
              pg_get_function_result(p.oid)        AS return_type,
              l.lanname     AS language,
              pg_get_functiondef(p.oid)           AS definition,
              d.description AS comment
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_language l ON l.oid = p.prolang
            LEFT JOIN pg_description d ON d.objoid = p.oid
            WHERE (COALESCE(? , '') = '' OR n.nspname = ?)
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            ORDER BY n.nspname, p.proname
            """.trimIndent(),
            { rs, _ ->
                FunctionDto(
                    schema = rs.getString("schema_name"),
                    name = rs.getString("function_name"),
                    arguments = rs.getString("arguments"),
                    returnType = rs.getString("return_type"),
                    language = rs.getString("language"),
                    definition = rs.getString("definition"),
                    comment = rs.getString("comment")
                )
            },
            schema,
            schema
        )

        val term = search?.trim().orEmpty()
        if (term.isEmpty()) return all

        val lower = term.lowercase()
        return all.filter { fn ->
            fn.name.lowercase().contains(lower) ||
            (fn.arguments?.lowercase()?.contains(lower) == true) ||
            (fn.comment?.lowercase()?.contains(lower) == true)
        }
    }

    fun applyFunctionDefinition(sql: String) {
        logger.info("Executing function SQL:\n{}", sql)
        jdbc.execute(sql)
    }

    fun deleteFunction(schema: String, name: String) {
        val dropSql = jdbc.query(
            """
            SELECT format(
                'DROP FUNCTION IF EXISTS %I.%I(%s)',
                n.nspname,
                p.proname,
                pg_get_function_identity_arguments(p.oid)
            ) AS drop_sql
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ?
              AND p.proname = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString("drop_sql") },
            schema,
            name
        ).firstOrNull()

        if (!dropSql.isNullOrBlank()) {
            logger.info("Executing function drop SQL: {}", dropSql)
            jdbc.execute(dropSql)
        }
    }

    fun executeTableSql(sql: String) {
        logger.info("Executing table SQL:\n{}", sql)
        jdbc.execute(sql)
    }

    fun dropTable(schema: String, table: String) {
        val sql = "DROP TABLE IF EXISTS \"$schema\".\"$table\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun createTable(schema: String, table: String, idStrategy: String = "serial") {
        val columnsSql = when (idStrategy.lowercase()) {
            "none" -> ""
            "int_identity" -> "id integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
            "bigint_identity" -> "id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
            "serial" -> "id serial PRIMARY KEY"
            "bigserial" -> "id bigserial PRIMARY KEY"
            "uuid" -> "id uuid PRIMARY KEY"
            else -> throw IllegalArgumentException("Invalid id strategy: $idStrategy")
        }

        val sql = "CREATE TABLE \"$schema\".\"$table\" ($columnsSql)"
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun dropIndex(schema: String, indexName: String) {
        val sql = "DROP INDEX IF EXISTS \"$schema\".\"$indexName\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun getTableDdl(schema: String, table: String): String {
        val columnsSql = jdbc.queryForObject(
            """
            SELECT string_agg(
                       format(
                           '  %I %s%s%s',
                           a.attname,
                           pg_catalog.format_type(a.atttypid, a.atttypmod),
                           CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                           CASE WHEN ad.adbin IS NOT NULL THEN ' DEFAULT ' || regexp_replace(
                               pg_get_expr(ad.adbin, ad.adrelid),
                               '(^|[^.])(fun_auth_[a-zA-Z0-9_]*)\s*\(',
                               E'\\1auth.\\2(',
                               'g'
                           ) ELSE '' END
                       ),
                       E',\n'
                       ORDER BY a.attnum
                   ) AS columns_sql
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
            WHERE n.nspname = ?
              AND c.relname = ?
              AND a.attnum > 0
              AND NOT a.attisdropped
            """.trimIndent(),
            String::class.java,
            schema,
            table
        )

        if (columnsSql.isNullOrBlank()) {
            return "-- Could not build DDL for $schema.$table"
        }

        val createSql = "CREATE TABLE \"$schema\".\"$table\" (\n$columnsSql\n);"

        val constraintsSql = jdbc.query(
            """
            SELECT format(
                'ALTER TABLE %I.%I ADD CONSTRAINT %I %s;',
                n.nspname,
                c.relname,
                con.conname,
                pg_get_constraintdef(con.oid, true)
            ) AS constraint_sql
            FROM pg_constraint con
            JOIN pg_class c ON c.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relname = ?
              AND con.contype IN ('p', 'u', 'f', 'c', 'x')
            ORDER BY con.contype, con.conname
            """.trimIndent(),
            { rs, _ -> rs.getString("constraint_sql") },
            schema,
            table
        ).filterNotNull()

        return if (constraintsSql.isEmpty()) {
            createSql
        } else {
            createSql + "\n\n" + constraintsSql.joinToString("\n")
        }
    }

    fun listPolicies(schema: String, table: String): List<PolicyDto> =
        jdbc.query(
            """
            SELECT policyname, cmd, roles, qual, with_check
            FROM pg_policies
            WHERE schemaname = ?
              AND tablename = ?
            ORDER BY policyname
            """.trimIndent(),
            { rs, _ ->
                val policyName = rs.getString("policyname")
                val cmd = rs.getString("cmd") ?: "ALL"
                val rolesArr = (rs.getArray("roles")?.array as? Array<*>)
                val roles = rolesArr
                    ?.mapNotNull { it?.toString() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?: "public"
                val usingExpr = rs.getString("qual")
                val withCheckExpr = rs.getString("with_check")
                val definitionSql = buildPolicyDefinitionSql(
                    schema = schema,
                    table = table,
                    policyName = policyName,
                    command = cmd,
                    roles = roles,
                    usingExpr = usingExpr,
                    withCheckExpr = withCheckExpr
                )

                PolicyDto(
                    name = policyName,
                    command = cmd,
                    roles = roles,
                    usingExpr = usingExpr,
                    withCheckExpr = withCheckExpr,
                    definitionSql = definitionSql
                )
            },
            schema,
            table
        )

    fun dropPolicy(schema: String, table: String, policyName: String) {
        val sql = "DROP POLICY IF EXISTS \"$policyName\" ON \"$schema\".\"$table\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun listTableGrants(schema: String, table: String): List<TableGrantDto> =
        jdbc.query(
            """
            SELECT
              grantee,
              string_agg(DISTINCT privilege_type, ',' ORDER BY privilege_type) AS privileges
            FROM information_schema.role_table_grants
            WHERE table_schema = ?
              AND table_name = ?
            GROUP BY grantee
            ORDER BY grantee
            """.trimIndent(),
            { rs, _ ->
                TableGrantDto(
                    grantee = rs.getString("grantee"),
                    privileges = rs.getString("privileges") ?: ""
                )
            },
            schema,
            table
        )

    fun grantTablePrivileges(schema: String, table: String, grantee: String, privileges: List<String>) {
        if (privileges.isEmpty()) return
        val sql = "GRANT ${privileges.joinToString(",")} ON TABLE \"$schema\".\"$table\" TO \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun revokeAllTablePrivileges(schema: String, table: String, grantee: String) {
        val sql = "REVOKE ALL PRIVILEGES ON TABLE \"$schema\".\"$table\" FROM \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun grantSequencePrivileges(schema: String, sequenceName: String, grantee: String, privileges: List<String>) {
        if (privileges.isEmpty()) return
        val sql = "GRANT ${privileges.joinToString(", ")} ON SEQUENCE \"$schema\".\"$sequenceName\" TO \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun listSequenceGrants(schema: String, sequenceName: String): List<TableGrantDto> =
        jdbc.query(
            """
            SELECT
              CASE WHEN a.grantee = 0 THEN 'public' ELSE r.rolname END AS grantee,
              string_agg(DISTINCT a.privilege_type, ',' ORDER BY a.privilege_type) AS privileges
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl, acldefault('S', c.relowner))) a
            LEFT JOIN pg_roles r ON r.oid = a.grantee
            WHERE c.relkind = 'S'
              AND n.nspname = ?
              AND c.relname = ?
            GROUP BY CASE WHEN a.grantee = 0 THEN 'public' ELSE r.rolname END
            ORDER BY grantee
            """.trimIndent(),
            { rs, _ ->
                TableGrantDto(
                    grantee = rs.getString("grantee"),
                    privileges = rs.getString("privileges") ?: ""
                )
            },
            schema,
            sequenceName
        )

    fun revokeAllSequencePrivileges(schema: String, sequenceName: String, grantee: String) {
        val sql = "REVOKE ALL PRIVILEGES ON SEQUENCE \"$schema\".\"$sequenceName\" FROM \"$grantee\""
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun isRlsEnabled(schema: String, table: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT c.relrowsecurity
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relname = ?
            """.trimIndent(),
            Boolean::class.java,
            schema,
            table
        ) ?: false

    fun enableRls(schema: String, table: String) {
        val sql = "ALTER TABLE \"$schema\".\"$table\" ENABLE ROW LEVEL SECURITY"
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun disableRls(schema: String, table: String) {
        val sql = "ALTER TABLE \"$schema\".\"$table\" DISABLE ROW LEVEL SECURITY"
        logger.info("Executing SQL: {}", sql)
        jdbc.execute(sql)
    }

    private fun buildPolicyDefinitionSql(
        schema: String,
        table: String,
        policyName: String,
        command: String,
        roles: String,
        usingExpr: String?,
        withCheckExpr: String?
    ): String {
        val sb = StringBuilder()
        sb.append("CREATE POLICY \"").append(policyName).append("\" ON \"")
            .append(schema).append("\".\"").append(table).append("\"\n")
            .append("  FOR ").append(command.uppercase())
            .append(" TO ").append(roles).append("\n")

        if (!usingExpr.isNullOrBlank()) {
            sb.append("  USING (").append(usingExpr).append(")")
            if (!withCheckExpr.isNullOrBlank()) sb.append("\n")
        }
        if (!withCheckExpr.isNullOrBlank()) {
            sb.append("  WITH CHECK (").append(withCheckExpr).append(")")
        }
        sb.append(";")
        return sb.toString()
    }

    /**
     * Returns a simplified view of table constraints (PK, single-column UNIQUEs and FKs)
     * used by the form-based table editor.
     */
    fun getTableConstraints(schema: String, table: String): TableConstraintsDto {
        // Primary key
        val pkRows = jdbc.query(
            """
            SELECT tc.constraint_name, kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_name = tc.constraint_name
             AND kcu.constraint_schema = tc.constraint_schema
             AND kcu.constraint_catalog = tc.constraint_catalog
            WHERE tc.table_schema = ?
              AND tc.table_name = ?
              AND tc.constraint_type = 'PRIMARY KEY'
            ORDER BY kcu.ordinal_position
            """.trimIndent(),
            { rs, _ -> rs.getString("constraint_name") to rs.getString("column_name") },
            schema,
            table
        )

        val pkConstraintName = pkRows.firstOrNull()?.first
        val pkColumns = pkRows.map { it.second }.toSet()

        // UNIQUE constraints (only single-column ones, mapped column -> constraint name)
        data class UniqueRow(val constraintName: String, val columnName: String)
        val uniqueRows = jdbc.query(
            """
            SELECT tc.constraint_name, kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_name = tc.constraint_name
             AND kcu.constraint_schema = tc.constraint_schema
             AND kcu.constraint_catalog = tc.constraint_catalog
            WHERE tc.table_schema = ?
              AND tc.table_name = ?
              AND tc.constraint_type = 'UNIQUE'
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """.trimIndent(),
            { rs, _ -> UniqueRow(
                constraintName = rs.getString("constraint_name"),
                columnName = rs.getString("column_name")
            ) },
            schema,
            table
        )

        val uniqueColumns = uniqueRows
            .groupBy { it.constraintName }
            .filter { (_, cols) -> cols.size == 1 }
            .values
            .associate { rows ->
                val row = rows.first()
                row.columnName to row.constraintName
            }

        // Foreign keys (only single-column, mapped column -> ForeignKeyInfoDto)
        data class FkRow(
            val constraintName: String,
            val columnName: String,
            val refSchema: String,
            val refTable: String,
            val refColumn: String
        )

        val fkRows = jdbc.query(
            """
            SELECT
              tc.constraint_name,
              kcu.column_name,
              ccu.table_schema  AS foreign_table_schema,
              ccu.table_name    AS foreign_table_name,
              ccu.column_name   AS foreign_column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_name = tc.constraint_name
             AND kcu.constraint_schema = tc.constraint_schema
             AND kcu.constraint_catalog = tc.constraint_catalog
            JOIN information_schema.constraint_column_usage ccu
              ON ccu.constraint_name = tc.constraint_name
             AND ccu.constraint_schema = tc.constraint_schema
             AND ccu.constraint_catalog = tc.constraint_catalog
            WHERE tc.table_schema = ?
              AND tc.table_name = ?
              AND tc.constraint_type = 'FOREIGN KEY'
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """.trimIndent(),
            { rs, _ ->
                FkRow(
                    constraintName = rs.getString("constraint_name"),
                    columnName = rs.getString("column_name"),
                    refSchema = rs.getString("foreign_table_schema"),
                    refTable = rs.getString("foreign_table_name"),
                    refColumn = rs.getString("foreign_column_name")
                )
            },
            schema,
            table
        )

        val foreignKeys = fkRows
            .groupBy { it.constraintName }
            .filter { (_, cols) -> cols.size == 1 }
            .values
            .associate { rows ->
                val row = rows.first()
                row.columnName to ForeignKeyInfoDto(
                    column = row.columnName,
                    refSchema = row.refSchema,
                    refTable = row.refTable,
                    refColumn = row.refColumn,
                    constraintName = row.constraintName
                )
            }

        return TableConstraintsDto(
            primaryKeyColumns = pkColumns,
            primaryKeyConstraintName = pkConstraintName,
            uniqueColumns = uniqueColumns,
            foreignKeys = foreignKeys
        )
    }

    /**
     * List non-primary-key indexes for a given table, with a best-effort
     * parse of columns and WHERE predicate.
     */
    fun listIndexes(schema: String, table: String): List<IndexDto> =
        jdbc.query(
            """
            SELECT
              idx.indexname,
              pg_get_indexdef(i.indexrelid) AS indexdef,
              i.indisunique,
              (SELECT COUNT(1)
               FROM pg_constraint c
               WHERE c.conindid = i.indexrelid
                 AND c.contype IN ('p','u')) AS constraint_count
            FROM pg_indexes idx
            JOIN pg_class t ON t.relname = idx.tablename
            JOIN pg_namespace n ON n.nspname = idx.schemaname AND n.oid = t.relnamespace
            JOIN pg_class ic ON ic.relname = idx.indexname
            JOIN pg_index i ON i.indexrelid = ic.oid
            WHERE idx.schemaname = ?
              AND idx.tablename = ?
              AND NOT i.indisprimary
            ORDER BY idx.indexname
            """.trimIndent(),
            { rs, _ ->
                val name = rs.getString("indexname")
                val def = rs.getString("indexdef") ?: ""
                val unique = rs.getBoolean("indisunique")
                val constraintCount = rs.getInt("constraint_count")

                // Very simple parse: columns between the first '(' and ')' after ON ...
                val colsPart = def.substringAfter('(' , "").substringBefore(')', "").trim()
                val wherePart = def.substringAfter(" WHERE ", "").ifBlank { null }

                IndexDto(
                    name = name,
                    unique = unique,
                    columns = colsPart.ifBlank { null },
                    whereClause = wherePart,
                    constraintBacked = constraintCount > 0,
                    definitionSql = if (def.endsWith(";")) def else "$def;"
                )
            },
            schema,
            table
        )

    // --- Custom types: ENUM ---

    fun listEnumTypes(schema: String? = null): List<EnumTypeDto> {
        // Postgres can't infer the parameter type for a bare "? IS NULL" check, so build the
        // schema filter conditionally instead of always binding two parameters.
        val schemaFilter = if (schema != null) "AND n.nspname = ?" else ""
        val rows = jdbc.query(
            """
            SELECT
              n.nspname AS schema_name,
              t.typname AS type_name,
              e.enumlabel AS value,
              d.description AS comment
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_enum e ON e.enumtypid = t.oid
            LEFT JOIN pg_description d ON d.objoid = t.oid AND d.objsubid = 0
            WHERE t.typtype = 'e'
              $schemaFilter
            ORDER BY n.nspname, t.typname, e.enumsortorder
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getString("schema_name") to rs.getString("type_name"),
                    rs.getString("value"),
                    rs.getString("comment")
                )
            },
            *listOfNotNull(schema).toTypedArray()
        )

        return rows.groupBy { it.first }
            .map { (schemaAndName, group) ->
                EnumTypeDto(
                    schema = schemaAndName.first,
                    name = schemaAndName.second,
                    values = group.map { it.second },
                    comment = group.first().third
                )
            }
            .sortedWith(compareBy({ it.schema }, { it.name }))
    }

    fun createEnumType(schema: String, name: String, values: List<String>, comment: String?) {
        val valuesSql = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
        val sql = "CREATE TYPE \"$schema\".\"$name\" AS ENUM ($valuesSql)"
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
        if (!comment.isNullOrBlank()) {
            setTypeComment(schema, name, comment)
        }
    }

    fun addEnumValue(schema: String, name: String, value: String, insertPosition: String, relativeToValue: String?) {
        val escapedValue = value.replace("'", "''")
        val position = when {
            insertPosition == "before" && !relativeToValue.isNullOrBlank() ->
                "BEFORE '${relativeToValue.replace("'", "''")}'"
            insertPosition == "after" && !relativeToValue.isNullOrBlank() ->
                "AFTER '${relativeToValue.replace("'", "''")}'"
            else -> ""
        }
        val sql = "ALTER TYPE \"$schema\".\"$name\" ADD VALUE IF NOT EXISTS '$escapedValue' $position".trim()
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun renameEnumValue(schema: String, name: String, from: String, to: String) {
        val sql = "ALTER TYPE \"$schema\".\"$name\" RENAME VALUE '${from.replace("'", "''")}' TO '${to.replace("'", "''")}'"
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun renameEnumType(schema: String, name: String, newName: String) {
        val sql = "ALTER TYPE \"$schema\".\"$name\" RENAME TO \"$newName\""
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun setTypeComment(schema: String, name: String, comment: String) {
        val sql = "COMMENT ON TYPE \"$schema\".\"$name\" IS '${comment.replace("'", "''")}'"
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun dropEnumType(schema: String, name: String, cascade: Boolean) {
        val sql = "DROP TYPE IF EXISTS \"$schema\".\"$name\"" + if (cascade) " CASCADE" else ""
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    // --- Custom types: DOMAIN ---

    fun listDomainTypes(schema: String? = null): List<DomainTypeDto> {
        // Same "? IS NULL" parameter-type-inference issue as listEnumTypes: build the filter
        // conditionally instead.
        val schemaFilter = if (schema != null) "AND n.nspname = ?" else ""
        return jdbc.query(
            """
            SELECT DISTINCT ON (n.nspname, t.typname)
              n.nspname AS schema_name,
              t.typname AS type_name,
              format_type(t.typbasetype, t.typtypmod) AS base_type,
              t.typnotnull AS not_null,
              t.typdefault AS default_value,
              con.conname AS check_name,
              pg_get_constraintdef(con.oid) AS check_def,
              d.description AS comment
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            LEFT JOIN pg_constraint con ON con.contypid = t.oid
            LEFT JOIN pg_description d ON d.objoid = t.oid AND d.objsubid = 0
            WHERE t.typtype = 'd'
              $schemaFilter
            ORDER BY n.nspname, t.typname, con.oid
            """.trimIndent(),
            { rs, _ ->
                val checkDef = rs.getString("check_def")
                val checkExpr = checkDef
                    ?.removePrefix("CHECK (")
                    ?.removeSuffix(")")

                DomainTypeDto(
                    schema = rs.getString("schema_name"),
                    name = rs.getString("type_name"),
                    baseType = rs.getString("base_type"),
                    notNull = rs.getBoolean("not_null"),
                    defaultValue = rs.getString("default_value"),
                    checkName = rs.getString("check_name"),
                    checkExpr = checkExpr,
                    comment = rs.getString("comment")
                )
            },
            *listOfNotNull(schema).toTypedArray()
        )
    }

    fun createDomainType(
        schema: String,
        name: String,
        baseType: String,
        notNull: Boolean,
        defaultValue: String?,
        checkExpr: String?,
        comment: String?
    ) {
        val sql = buildString {
            append("CREATE DOMAIN \"$schema\".\"$name\" AS $baseType")
            if (!defaultValue.isNullOrBlank()) append(" DEFAULT $defaultValue")
            if (notNull) append(" NOT NULL")
            if (!checkExpr.isNullOrBlank()) append(" CHECK ($checkExpr)")
        }
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
        if (!comment.isNullOrBlank()) {
            setDomainComment(schema, name, comment)
        }
    }

    fun alterDomainDefault(schema: String, name: String, defaultValue: String?) {
        val sql = if (defaultValue.isNullOrBlank()) {
            "ALTER DOMAIN \"$schema\".\"$name\" DROP DEFAULT"
        } else {
            "ALTER DOMAIN \"$schema\".\"$name\" SET DEFAULT $defaultValue"
        }
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun alterDomainNotNull(schema: String, name: String, notNull: Boolean) {
        val sql = "ALTER DOMAIN \"$schema\".\"$name\" ${if (notNull) "SET" else "DROP"} NOT NULL"
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun alterDomainCheck(schema: String, name: String, oldCheckName: String?, newCheckExpr: String?) {
        if (!oldCheckName.isNullOrBlank()) {
            val dropSql = "ALTER DOMAIN \"$schema\".\"$name\" DROP CONSTRAINT \"$oldCheckName\""
            logger.info("Executing type SQL: {}", dropSql)
            jdbc.execute(dropSql)
        }
        if (!newCheckExpr.isNullOrBlank()) {
            val addSql = "ALTER DOMAIN \"$schema\".\"$name\" ADD CONSTRAINT \"${name}_check\" CHECK ($newCheckExpr)"
            logger.info("Executing type SQL: {}", addSql)
            jdbc.execute(addSql)
        }
    }

    fun renameDomain(schema: String, name: String, newName: String) {
        val sql = "ALTER DOMAIN \"$schema\".\"$name\" RENAME TO \"$newName\""
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun setDomainComment(schema: String, name: String, comment: String) {
        val sql = "COMMENT ON DOMAIN \"$schema\".\"$name\" IS '${comment.replace("'", "''")}'"
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }

    fun dropDomain(schema: String, name: String, cascade: Boolean) {
        val sql = "DROP DOMAIN IF EXISTS \"$schema\".\"$name\"" + if (cascade) " CASCADE" else ""
        logger.info("Executing type SQL: {}", sql)
        jdbc.execute(sql)
    }
}
