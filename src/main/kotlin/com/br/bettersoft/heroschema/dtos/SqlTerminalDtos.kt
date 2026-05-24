package com.br.bettersoft.heroschema.dtos

data class SqlTerminalRequestDto(
    val schema: String,
    val sql: String,
    val maxRows: Int? = 200
)

data class SqlStatementResultDto(
    val statement: String,
    val kind: String,
    val rowCount: Int? = null,
    val columns: List<String> = emptyList(),
    val rows: List<List<String?>> = emptyList(),
    val message: String? = null,
    val elapsedMs: Long = 0
)

data class SqlTerminalResponseDto(
    val schema: String,
    val ok: Boolean,
    val statements: List<SqlStatementResultDto>,
    val error: String? = null
)

data class SqlHintsDto(
    val schema: String,
    val suggestions: List<String>
)

