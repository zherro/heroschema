package com.br.bettersoft.heroschema.service

import com.br.bettersoft.heroschema.dtos.SqlHintsDto
import com.br.bettersoft.heroschema.dtos.SqlTerminalResponseDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.stereotype.Service

@Service
class SqlTerminalService(
    private val repo: MetadataRepository
) {

    fun execute(schema: String, sql: String, maxRows: Int): SqlTerminalResponseDto {
        val normalizedSchema = schema.trim()
        require(normalizedSchema.isNotBlank()) { "Schema is required" }
        require(sql.trim().isNotBlank()) { "SQL is required" }

        val availableSchemas = repo.listSchemas()
        require(availableSchemas.contains(normalizedSchema)) { "Schema '$normalizedSchema' not found" }

        val statements = splitSqlStatements(sql)
        require(statements.isNotEmpty()) { "No SQL statements found" }

        val unsupported = statements.firstOrNull { !isSupportedStatement(it) }
        require(unsupported == null) {
            "Only SELECT, WITH, VALUES, INSERT, UPDATE, DELETE and CTE statements are allowed"
        }

        val results = repo.executeSqlStatements(normalizedSchema, statements, maxRows)
        return SqlTerminalResponseDto(
            schema = normalizedSchema,
            ok = true,
            statements = results
        )
    }

    fun hints(schema: String): SqlHintsDto {
        val normalizedSchema = schema.trim()
        val availableSchemas = repo.listSchemas()
        require(availableSchemas.contains(normalizedSchema)) { "Schema '$normalizedSchema' not found" }

        val items = linkedSetOf<String>()
        items.addAll(baseKeywords())
        items.addAll(repo.listTables(normalizedSchema).map { "\"$it\"" })
        items.addAll(repo.listSqlColumns(normalizedSchema).map { "\"$it\"" })
        items.addAll(repo.listSqlFunctions(normalizedSchema))

        return SqlHintsDto(
            schema = normalizedSchema,
            suggestions = items.toList().sortedBy { it.lowercase() }
        )
    }

    private fun isSupportedStatement(statement: String): Boolean {
        val token = stripLeadingComments(statement)
            .trimStart()
            .takeWhile { !it.isWhitespace() }
            .lowercase()

        return token in setOf("select", "with", "values", "insert", "update", "delete", "call")
    }

    private fun stripLeadingComments(sql: String): String {
        var text = sql.trimStart()

        while (true) {
            if (text.startsWith("--")) {
                val idx = text.indexOf('\n')
                text = if (idx >= 0) text.substring(idx + 1).trimStart() else ""
                continue
            }

            if (text.startsWith("/*")) {
                val idx = text.indexOf("*/")
                text = if (idx >= 0) text.substring(idx + 2).trimStart() else ""
                continue
            }

            return text
        }
    }

    private fun baseKeywords(): List<String> = listOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "DELETE", "WITH",
        "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "GROUP BY", "ORDER BY", "LIMIT", "OFFSET",
        "COUNT", "DISTINCT", "RETURNING", "CALL", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END"
    )

    private fun splitSqlStatements(script: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()

        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var dollarTag: String? = null
        var i = 0

        while (i < script.length) {
            val c = script[i]
            val next = if (i + 1 < script.length) script[i + 1] else '\u0000'

            if (inLineComment) {
                current.append(c)
                if (c == '\n') inLineComment = false
                i++
                continue
            }

            if (inBlockComment) {
                current.append(c)
                if (c == '*' && next == '/') {
                    current.append(next)
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && dollarTag == null) {
                if (c == '-' && next == '-') {
                    current.append(c).append(next)
                    inLineComment = true
                    i += 2
                    continue
                }

                if (c == '/' && next == '*') {
                    current.append(c).append(next)
                    inBlockComment = true
                    i += 2
                    continue
                }

                if (c == ';') {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) parts.add(stmt)
                    current.setLength(0)
                    i++
                    continue
                }

                if (c == '$') {
                    val tag = readDollarTag(script, i)
                    if (tag != null) {
                        dollarTag = tag
                        current.append(tag)
                        i += tag.length
                        continue
                    }
                }
            } else if (dollarTag != null && c == '$') {
                val candidate = readDollarTag(script, i)
                if (candidate != null && candidate == dollarTag) {
                    current.append(candidate)
                    i += candidate.length
                    dollarTag = null
                    continue
                }
            }

            if (c == '\'' && !inDoubleQuote && dollarTag == null) {
                inSingleQuote = !inSingleQuote
            } else if (c == '"' && !inSingleQuote && dollarTag == null) {
                inDoubleQuote = !inDoubleQuote
            }

            current.append(c)
            i++
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }

    private fun readDollarTag(text: String, start: Int): String? {
        if (text[start] != '$') return null
        var i = start + 1
        while (i < text.length && text[i] != '$') {
            val ch = text[i]
            if (!(ch == '_' || ch.isLetterOrDigit())) {
                return null
            }
            i++
        }
        if (i >= text.length || text[i] != '$') return null
        return text.substring(start, i + 1)
    }
}

