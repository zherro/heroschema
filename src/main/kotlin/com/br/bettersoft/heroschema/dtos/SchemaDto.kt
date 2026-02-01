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

data class FunctionDto(
    val schema: String,
    val name: String,
    val arguments: String?,
    val returnType: String?,
    val language: String?,
    val definition: String?,
    val comment: String?
)
