package com.br.bettersoft.heroschema.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class AuthScriptDefinition(
    val id: String,
    val path: String,
    val description: String
)

data class AuthScriptsResult(
    val executed: List<AuthScriptDefinition>,
    val errors: List<Throwable>
)

data class AuthInstallStatus(
    val complete: Boolean,
    val hasErrors: Boolean,
    val lastErrorMessage: String?,
    val scriptStatuses: Map<String, String>
)

@Service
class AuthScriptsService(
    private val jdbcTemplate: JdbcTemplate
) {

    private val logger = LoggerFactory.getLogger(AuthScriptsService::class.java)
    private val mapper = jacksonObjectMapper()

    private val sqlScripts: List<AuthScriptDefinition> by lazy {
        val resource = ClassPathResource("auth-scripts/scripts.json")
        resource.inputStream.use { input ->
            mapper.readValue(input)
        }
    }

    fun listScripts(): List<AuthScriptDefinition> {
        val infra = AuthScriptDefinition(
            id = "000_infra_auth_install_log",
            path = "[internal] ensureLogTable()",
            description = "Infra interna: cria/garante a tabela de log auth_install_log."
        )
        return listOf(infra) + sqlScripts
    }

    fun getStatus(): AuthInstallStatus {
        data class LogRow(
            val scriptId: String,
            val status: String,
            val ranAt: java.sql.Timestamp,
            val errorMessage: String?
        )

        val logs = try {
            jdbcTemplate.query(
                "SELECT script_id, status, ran_at, error_message FROM auth.auth_install_log",
                { rs, _ ->
                    LogRow(
                        rs.getString("script_id"),
                        rs.getString("status"),
                        rs.getTimestamp("ran_at"),
                        rs.getString("error_message")
                    )
                }
            )
        } catch (ex: Exception) {
            emptyList()
        }

        val rawStatuses = logs.associate { it.scriptId to it.status }

        val effectiveStatuses = mutableMapOf<String, String>()

        // Se existe qualquer log de script real, assumimos que a infra (ensureLogTable) rodou com sucesso.
        val hasAnyRealLog = sqlScripts.any { rawStatuses[it.id] != null }
        effectiveStatuses["000_infra_auth_install_log"] = if (hasAnyRealLog) "SUCCESS" else "Pendente"

        effectiveStatuses.putAll(rawStatuses)

        val hasErrors = sqlScripts.any { effectiveStatuses[it.id] == "FAILED" }
        val complete = sqlScripts.isNotEmpty() && sqlScripts.all { effectiveStatuses[it.id] == "SUCCESS" } && hasAnyRealLog

        val lastErrorMsg = logs
            .filter { it.status == "FAILED" }
            .maxByOrNull { it.ranAt.time }
            ?.errorMessage

        return AuthInstallStatus(
            complete = complete,
            hasErrors = hasErrors,
            lastErrorMessage = lastErrorMsg,
            scriptStatuses = effectiveStatuses
        )
    }

    fun runAll(): AuthScriptsResult {
        ensureLogTable()
        data class LogRow(val scriptId: String, val status: String)
        val existingLogs = jdbcTemplate.query(
            "SELECT script_id, status FROM auth.auth_install_log",
            { rs, _ -> LogRow(rs.getString("script_id"), rs.getString("status")) }
        ).associateBy { it.scriptId }

        val allScripts = sqlScripts

        // Determine starting point:
        // 1) If there is a FAILED script in history, continue from the first failed.
        // 2) Otherwise, continue from the first script not logged yet.
        val firstFailedIndex = allScripts.indexOfFirst { s ->
            existingLogs[s.id]?.status == "FAILED"
        }

        val firstNotRunIndex = allScripts.indexOfFirst { s ->
            existingLogs[s.id] == null
        }

        val startIndex = when {
            firstFailedIndex >= 0 -> firstFailedIndex
            firstNotRunIndex >= 0 -> firstNotRunIndex
            else -> -1
        }

        if (startIndex == -1) {
            // Nada novo para rodar
            return AuthScriptsResult(executed = emptyList(), errors = emptyList())
        }

        val executed = mutableListOf<AuthScriptDefinition>()
        val errors = mutableListOf<Throwable>()

        for (i in startIndex until allScripts.size) {
            val script = allScripts[i]
            try {
                val resource = ClassPathResource(script.path)
                val sql = resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                logger.info("Executing auth script {} from {}:\n{}", script.id, script.path, sql)
                jdbcTemplate.execute(sql)
                upsertLog(script.id, "SUCCESS", null)
                executed.add(script)
            } catch (ex: Throwable) {
                logger.error("Error executing auth script {} from {}. SQL was:\n{}\nError: {}", script.id, script.path, try {
                    ClassPathResource(script.path).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (reRead: Throwable) {
                    "<failed to re-read SQL: ${reRead.message}>"
                }, ex.message, ex)
                upsertLog(script.id, "FAILED", ex.message)
                errors.add(ex)
                // Para na primeira falha desta rodada
                break
            }
        }

        return AuthScriptsResult(executed = executed, errors = errors)
    }

    fun resetAndRunAll(): AuthScriptsResult {
        ensureLogTable()
        jdbcTemplate.execute("TRUNCATE TABLE auth.auth_install_log")
        return runAll()
    }

    private fun upsertLog(scriptId: String, status: String, error: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO auth.auth_install_log (script_id, status, ran_at, error_message)
            VALUES (?, ?, now(), ?)
            ON CONFLICT (script_id)
            DO UPDATE SET
              status = EXCLUDED.status,
              ran_at = EXCLUDED.ran_at,
              error_message = EXCLUDED.error_message
            """.trimIndent(),
            scriptId,
            status,
            error
        )
    }

    private fun ensureLogTable() {
        val ddl = """
            CREATE SCHEMA IF NOT EXISTS auth;
            CREATE SCHEMA IF NOT EXISTS public;

            CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA auth;
            CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
                        
            CREATE TABLE IF NOT EXISTS auth.auth_install_log (
              id            serial PRIMARY KEY,
              script_id     text NOT NULL UNIQUE,
              status        text NOT NULL,
              ran_at        timestamptz NOT NULL DEFAULT now(),
              error_message text
            )
        """.trimIndent()
        jdbcTemplate.execute(ddl)
    }
}
