package com.br.bettersoft.heroschema.repository

import com.br.bettersoft.heroschema.dtos.ColumnDto
import com.br.bettersoft.heroschema.dtos.FunctionDto
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class MetadataRepository(
    private val jdbc: JdbcTemplate
) {

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
            jdbc.execute(dropSql)
        }
    }
}
