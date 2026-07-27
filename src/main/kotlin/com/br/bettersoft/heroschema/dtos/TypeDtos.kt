package com.br.bettersoft.heroschema.dtos

data class EnumTypeDto(
    val schema: String,
    val name: String,
    val values: List<String>,
    val comment: String?
)

data class DomainTypeDto(
    val schema: String,
    val name: String,
    val baseType: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val checkName: String?,
    val checkExpr: String?,
    val comment: String?
)

/**
 * Form backing object for creating a new enum type.
 * values holds one entry per dynamically-added row (mirrors the column-row-template pattern).
 */
data class EnumTypeFormDto(
    var schema: String = "",
    var name: String = "",
    var values: MutableList<String> = mutableListOf(""),
    var comment: String? = null
)

/**
 * Form backing object for editing an existing enum type.
 * newValue/insertPosition/relativeToValue add one value at a time (Postgres has no bulk ADD VALUE).
 * renameFrom/renameTo renames a single existing value. Postgres does not support removing enum
 * values, so no "drop value" field exists here.
 */
data class EnumTypeEditFormDto(
    var schema: String = "",
    var name: String = "",
    var newTypeName: String? = null,
    var comment: String? = null,
    var newValue: String? = null,
    var insertPosition: String = "end", // "end" | "before" | "after"
    var relativeToValue: String? = null,
    var renameFrom: String? = null,
    var renameTo: String? = null
)

data class DomainTypeFormDto(
    var schema: String = "",
    var name: String = "",
    var baseType: String = "text",
    var notNull: Boolean = false,
    var defaultValue: String? = null,
    var checkExpr: String? = null,
    var comment: String? = null
)

data class DomainTypeEditFormDto(
    var schema: String = "",
    var name: String = "",
    var newTypeName: String? = null,
    var notNull: Boolean = false,
    var defaultValue: String? = null,
    var checkExpr: String? = null,
    var comment: String? = null
)
