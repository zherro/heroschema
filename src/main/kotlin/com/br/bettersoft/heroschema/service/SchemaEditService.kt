package com.br.bettersoft.heroschema.service

import com.br.bettersoft.heroschema.dtos.TableEditFormDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.stereotype.Service

@Service
class SchemaEditService(
    private val repo: MetadataRepository
) {

    /**
     * Generates the full list of DDL statements required to apply the changes
     * described by [form] to the underlying PostgreSQL table.
     */
    fun buildAlterStatements(form: TableEditFormDto): List<String> {
        val schema = form.schema
        val table = form.table

        val existingColumns = repo.listColumns(schema, table)
        val constraints = repo.getTableConstraints(schema, table)
        val existingIndexes = repo.listIndexes(schema, table)

        val existingByName = existingColumns.associateBy { it.name }
        val submitted = form.columns
            .filter { it.name.isNotBlank() || !it.originalName.isNullOrBlank() }

        val statements = mutableListOf<String>()
        val fullTable = "\"$schema\".\"$table\""

        // Column-level changes (add/rename/type/nullability/default/comment)
        for (colForm in submitted) {
            val originalName = colForm.originalName?.ifBlank { null }
            val newName = colForm.name.trim()
            val existing = originalName?.let { existingByName[it] }

            if (existing == null && newName.isNotBlank()) {
                // New column
                val sb = StringBuilder()
                sb.append("ALTER TABLE $fullTable ADD COLUMN \"$newName\" ${colForm.type}")
                if (!colForm.nullable) sb.append(" NOT NULL")
                colForm.defaultValue?.takeIf { it.isNotBlank() }?.let { defaultExpr ->
                    sb.append(" DEFAULT $defaultExpr")
                }
                statements.add(sb.toString())
            } else if (existing != null) {
                val currentName = originalName!!

                // Rename
                if (newName.isNotBlank() && newName != currentName) {
                    statements.add("ALTER TABLE $fullTable RENAME COLUMN \"$currentName\" TO \"$newName\"")
                }

                val effectiveName = if (newName.isNotBlank()) newName else currentName

                // Type change
                if (colForm.type.isNotBlank() && colForm.type != existing.type) {
                    statements.add("ALTER TABLE $fullTable ALTER COLUMN \"$effectiveName\" TYPE ${colForm.type}")
                }

                // Nullability
                if (colForm.nullable != existing.nullable) {
                    if (colForm.nullable) {
                        statements.add("ALTER TABLE $fullTable ALTER COLUMN \"$effectiveName\" DROP NOT NULL")
                    } else {
                        statements.add("ALTER TABLE $fullTable ALTER COLUMN \"$effectiveName\" SET NOT NULL")
                    }
                }

                // Default value
                val newDefault = colForm.defaultValue?.takeIf { it.isNotBlank() }
                val currDefault = existing.defaultValue?.takeIf { it.isNotBlank() }
                if (newDefault != currDefault) {
                    if (newDefault == null) {
                        statements.add("ALTER TABLE $fullTable ALTER COLUMN \"$effectiveName\" DROP DEFAULT")
                    } else {
                        statements.add("ALTER TABLE $fullTable ALTER COLUMN \"$effectiveName\" SET DEFAULT $newDefault")
                    }
                }

                // Comment
                val newComment = colForm.comment?.takeIf { it.isNotBlank() }
                val currComment = existing.comment?.takeIf { it.isNotBlank() }
                if (newComment != currComment) {
                    val commentSql = newComment?.let { comment ->
                        val escaped = comment.replace("'", "''")
                        "COMMENT ON COLUMN $fullTable.\"$effectiveName\" IS '$escaped'"
                    } ?: "COMMENT ON COLUMN $fullTable.\"$effectiveName\" IS NULL"
                    statements.add(commentSql)
                }
            }
        }

        // Primary key (allow multi-column via checkboxes)
        val desiredPkColumns = submitted
            .filter { it.primaryKey && it.name.isNotBlank() }
            .map { it.name.trim() }
            .toSet()
        val currentPkColumns = constraints.primaryKeyColumns

        val pkName = constraints.primaryKeyConstraintName
            ?: if (desiredPkColumns.isNotEmpty() || currentPkColumns.isNotEmpty()) "pk_$table" else null

        if (pkName != null && currentPkColumns != desiredPkColumns) {
            if (currentPkColumns.isNotEmpty()) {
                statements.add("ALTER TABLE $fullTable DROP CONSTRAINT \"$pkName\"")
            }
            if (desiredPkColumns.isNotEmpty()) {
                val colsList = desiredPkColumns.joinToString(", ") { "\"$it\"" }
                statements.add("ALTER TABLE $fullTable ADD CONSTRAINT \"$pkName\" PRIMARY KEY ($colsList)")
            }
        }

        // Unique constraints (single-column)
        for (colForm in submitted) {
            val colName = colForm.name.ifBlank { colForm.originalName ?: continue }
            val hasUniqueNow = colForm.unique
            val existingConstraintName = constraints.uniqueColumns[colName]

            if (!hasUniqueNow && existingConstraintName != null) {
                statements.add("ALTER TABLE $fullTable DROP CONSTRAINT \"$existingConstraintName\"")
            } else if (hasUniqueNow && existingConstraintName == null) {
                statements.add("ALTER TABLE $fullTable ADD CONSTRAINT \"uq_${table}_$colName\" UNIQUE (\"$colName\")")
            }
        }

        // Foreign keys (single-column)
        for (colForm in submitted) {
            val colName = colForm.name.ifBlank { colForm.originalName ?: continue }
            val currentFk = constraints.foreignKeys[colName]
            val wantsFk = colForm.foreignKey
            val hasAllRef = !colForm.refSchema.isNullOrBlank() &&
                    !colForm.refTable.isNullOrBlank() &&
                    !colForm.refColumn.isNullOrBlank()

            if (!wantsFk && currentFk != null) {
                statements.add("ALTER TABLE $fullTable DROP CONSTRAINT \"${currentFk.constraintName}\"")
            } else if (wantsFk && hasAllRef) {
                val refChanged = currentFk == null ||
                        currentFk.refSchema != colForm.refSchema ||
                        currentFk.refTable != colForm.refTable ||
                        currentFk.refColumn != colForm.refColumn

                if (currentFk != null && refChanged) {
                    statements.add("ALTER TABLE $fullTable DROP CONSTRAINT \"${currentFk.constraintName}\"")
                }

                if (refChanged) {
                    val fkName = currentFk?.constraintName ?: "fk_${table}_$colName"
                    statements.add(
                        "ALTER TABLE $fullTable ADD CONSTRAINT \"$fkName\" FOREIGN KEY (\"$colName\") " +
                                "REFERENCES \"${colForm.refSchema}\".\"${colForm.refTable}\"(\"${colForm.refColumn}\")"
                    )
                }
            }
        }

        // New index (composite, unique, partial)
        val editIndexOriginalName = form.editIndexOriginalName?.trim().orEmpty()
        val newIndexName = form.newIndexName?.trim().orEmpty()
        val newIndexColumns = form.newIndexColumns?.trim().orEmpty()
        val newIndexWhere = form.newIndexWhere?.trim().orEmpty()
        val newIndexUnique = form.newIndexUnique

        // If we are editing an existing index, drop the old one first (when not constraint-backed)
        if (editIndexOriginalName.isNotEmpty()) {
            val existingIndex = existingIndexes.firstOrNull { it.name == editIndexOriginalName }
            if (existingIndex != null && !existingIndex.constraintBacked) {
                statements.add("DROP INDEX IF EXISTS \"$schema\".\"$editIndexOriginalName\"")
            }
        }

        if (newIndexName.isNotEmpty() && newIndexColumns.isNotEmpty()) {
            val colsSql = newIndexColumns.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ") { "\"$it\"" }

            if (colsSql.isNotEmpty()) {
                val uniqueSql = if (newIndexUnique) "UNIQUE " else ""
                val whereSql = if (newIndexWhere.isNotEmpty()) " WHERE $newIndexWhere" else ""
                val idxSql =
                    "CREATE ${uniqueSql}INDEX \"$newIndexName\" ON $fullTable ($colsSql)$whereSql"
                statements.add(idxSql)
            }
        }

        return statements
    }
}
