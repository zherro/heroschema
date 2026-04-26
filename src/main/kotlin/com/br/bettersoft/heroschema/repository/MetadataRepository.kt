package com.br.bettersoft.heroschema.repository

import com.br.bettersoft.heroschema.dtos.ColumnDto
import com.br.bettersoft.heroschema.dtos.FunctionDto
import com.br.bettersoft.heroschema.dtos.ForeignKeyInfoDto
import com.br.bettersoft.heroschema.dtos.TableConstraintsDto
import com.br.bettersoft.heroschema.dtos.IndexDto
import com.br.bettersoft.heroschema.dtos.PolicyDto
import com.br.bettersoft.heroschema.dtos.TableGrantDto
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

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
                c.is_nullable,
                c.column_default,
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
                ColumnDto(
                    name = rs.getString("column_name"),
                    type = rs.getString("data_type"),
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
                    constraintBacked = constraintCount > 0
                )
            },
            schema,
            table
        )
}
