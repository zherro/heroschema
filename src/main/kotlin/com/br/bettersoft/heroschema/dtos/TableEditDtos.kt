package com.br.bettersoft.heroschema.dtos

data class ForeignKeyInfoDto(
    val column: String,
    val refSchema: String,
    val refTable: String,
    val refColumn: String,
    val constraintName: String
)

/**
 * Simplified view of table constraints used by the form editor.
 * - primaryKeyColumns: set of column names that belong to the (single) PK.
 * - primaryKeyConstraintName: existing PK constraint name, if any.
 * - uniqueColumns: map of column name -> unique constraint name (only single-column UNIQUEs).
 * - foreignKeys: map of column name -> foreign key info (only single-column FKs).
 */
data class TableConstraintsDto(
    val primaryKeyColumns: Set<String>,
    val primaryKeyConstraintName: String?,
    val uniqueColumns: Map<String, String>,
    val foreignKeys: Map<String, ForeignKeyInfoDto>
)

/**
 * Single column configuration in the form UI.
 */
data class ColumnFormDto(
    var originalName: String? = null,
    var name: String = "",
    var type: String = "",
    var nullable: Boolean = true,
    var defaultValue: String? = null,
    var comment: String? = null,
    var primaryKey: Boolean = false,
    var unique: Boolean = false,
    var foreignKey: Boolean = false,
    var refSchema: String? = null,
    var refTable: String? = null,
    var refColumn: String? = null
)

/**
 * Form backing object for table editing.
 */
data class TableEditFormDto(
    var schema: String = "",
    var table: String = "",
    var columns: MutableList<ColumnFormDto> = mutableListOf(),
    var editIndexOriginalName: String? = null,
    var newIndexName: String? = null,
    var newIndexColumns: String? = null,
    var newIndexUnique: Boolean = false,
    var newIndexWhere: String? = null
)
