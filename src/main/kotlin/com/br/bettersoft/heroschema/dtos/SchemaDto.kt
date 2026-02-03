package com.br.bettersoft.heroschema.dtos

data class SchemaDto(
    val name: String
)

data class TableDto(
    val schema: String,
    val name: String
)

data class SchemaWithTablesDto(
    val name: String,
    val tables: List<String>
)

data class ColumnDto(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val comment: String?
)

/**
 * Column view model with constraint info for schema/table visualization.
 */
data class ColumnWithConstraintsDto(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val comment: String?,
    val primaryKey: Boolean,
    val unique: Boolean,
    val fkRef: String?
)

/**
 * Basic index information used for visualization and index creation helpers.
 */
data class IndexDto(
    val name: String,
    val unique: Boolean,
    val columns: String?,
    val whereClause: String?,
    val constraintBacked: Boolean
)

data class FunctionDto(
    val schema: String,
    val name: String,
    val arguments: String?,
    val returnType: String?,
    val language: String?,
    val definition: String?,
    val comment: String?
)
