package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.dtos.ColumnFormDto
import com.br.bettersoft.heroschema.dtos.ColumnWithConstraintsDto
import com.br.bettersoft.heroschema.dtos.SchemaWithTablesDto
import com.br.bettersoft.heroschema.dtos.TableEditFormDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/schemas")
class SchemaController(
    private val repo: MetadataRepository
) {

    @GetMapping
    fun page(
        @RequestParam(required = false) schema: String?,
        @RequestParam(required = false) table: String?,
        model: Model
    ): String {

        val schemas = repo.listSchemas()
        model.addAttribute("schemas", schemas)
        model.addAttribute("selectedSchema", schema)
        model.addAttribute("selectedTable", table)
        model.addAttribute("page", "schemas")

        val schemaItems = schemas.map { schemaName ->
            SchemaWithTablesDto(
                name = schemaName,
                tables = repo.listTables(schemaName)
            )
        }
        model.addAttribute("schemaItems", schemaItems)

        if (schema != null && table != null) {
            val columns = repo.listColumns(schema, table)
            val constraints = repo.getTableConstraints(schema, table)

            val columnViews = columns.map { col ->
                val isPk = constraints.primaryKeyColumns.contains(col.name)
                val isUnique = constraints.uniqueColumns.containsKey(col.name)
                val fkInfo = constraints.foreignKeys[col.name]
                val fkRef = fkInfo?.let { "${it.refSchema}.${it.refTable}.${it.refColumn}" }

                ColumnWithConstraintsDto(
                    name = col.name,
                    type = col.type,
                    nullable = col.nullable,
                    defaultValue = col.defaultValue,
                    comment = col.comment,
                    primaryKey = isPk,
                    unique = isUnique,
                    fkRef = fkRef
                )
            }

            model.addAttribute("columns", columnViews)
            model.addAttribute("pageTitle", "$schema.$table")
        } else {
            model.addAttribute("pageTitle", "Schemas / Tables")
        }

        model.addAttribute("sidebar", "fragments/schemas-sidebar")
        model.addAttribute("content", "fragments/schemas-content")

        return "layout"
    }

    @PostMapping("/schema/rename")
    fun renameSchema(
        @RequestParam oldName: String,
        @RequestParam newName: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.renameSchema(oldName, newName)
            redirect.addFlashAttribute("message", "Schema $oldName renamed to $newName")
            "redirect:/schemas?schema=$newName"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error renaming schema: ${ex.message}")
            "redirect:/schemas?schema=$oldName"
        }
    }

    @PostMapping("/schema/delete")
    fun deleteSchema(
        @RequestParam name: String,
        @RequestParam(defaultValue = "false") cascade: Boolean,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.dropSchema(name, cascade)
            redirect.addFlashAttribute("message", "Schema $name deleted")
            "redirect:/schemas"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error deleting schema: ${ex.message}")
            "redirect:/schemas?schema=$name"
        }
    }

    @PostMapping("/schema/create")
    fun createSchema(
        @RequestParam name: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.createSchema(name)
            redirect.addFlashAttribute("message", "Schema $name created")
            "redirect:/schemas?schema=$name"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error creating schema: ${ex.message}")
            "redirect:/schemas"
        }
    }

    @GetMapping("/edit")
    fun editPage(
        @RequestParam schema: String,
        @RequestParam table: String,
        model: Model
    ): String {
        val columns = repo.listColumns(schema, table)
        val constraints = repo.getTableConstraints(schema, table)
        val allSchemas = repo.listSchemas()
        val indexes = repo.listIndexes(schema, table)

        val formColumns = columns.map { col ->
            val isPk = constraints.primaryKeyColumns.contains(col.name)
            val isUnique = constraints.uniqueColumns.containsKey(col.name)
            val fkInfo = constraints.foreignKeys[col.name]

            ColumnFormDto(
                originalName = col.name,
                name = col.name,
                type = col.type,
                nullable = col.nullable,
                defaultValue = col.defaultValue,
                comment = col.comment,
                primaryKey = isPk,
                unique = isUnique,
                foreignKey = fkInfo != null,
                refSchema = fkInfo?.refSchema,
                refTable = fkInfo?.refTable,
                refColumn = fkInfo?.refColumn
            )
        }.toMutableList()

        val form = TableEditFormDto(
            schema = schema,
            table = table,
            columns = formColumns
        )

        // Common PostgreSQL column types for the type <select>
        val typeOptions = listOf(
            "integer",
            "bigint",
            "smallint",
            "serial",
            "bigserial",
            "uuid",
            "text",
            "varchar(255)",
            "boolean",
            "timestamp without time zone",
            "timestamp with time zone",
            "date",
            "time without time zone",
            "numeric",
            "jsonb"
        )

        model.addAttribute("page", "schemas")
        model.addAttribute("pageTitle", "Edit table $schema.$table")
        model.addAttribute("selectedSchema", schema)
        model.addAttribute("selectedTable", table)
        model.addAttribute("form", form)
        model.addAttribute("typeOptions", typeOptions)
        model.addAttribute("schemaOptions", allSchemas)
        model.addAttribute("indexes", indexes)
        model.addAttribute("content", "fragments/table-edit")
        return "layout"
    }

    // --- JSON helpers for FK selects (searchable schema/table/column) ---

    @GetMapping("/fk/tables", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun fkTables(@RequestParam schema: String): List<String> =
        repo.listTables(schema)

    @GetMapping("/fk/columns", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun fkColumns(
        @RequestParam schema: String,
        @RequestParam table: String
    ): List<String> =
        repo.listColumns(schema, table).map { it.name }

    @PostMapping("/edit")
    fun applyEdit(
        @ModelAttribute form: TableEditFormDto,
        redirect: RedirectAttributes
    ): String {
        val schema = form.schema
        val table = form.table

        val existingColumns = repo.listColumns(schema, table)
        val constraints = repo.getTableConstraints(schema, table)

        val existingByName = existingColumns.associateBy { it.name }

        val submitted = form.columns.filter { it.name.isNotBlank() || !it.originalName.isNullOrBlank() }

        val statements = mutableListOf<String>()
        val fullTable = "\"$schema\".\"$table\""

        // Column-level changes (add/rename/type/nullability/default/comment)
        for (colForm in submitted) {
            val originalName = colForm.originalName?.ifBlank { null }
            val newName = colForm.name.trim()
            val existing = originalName?.let { existingByName[it] }

            // Drop column
            if (colForm.drop && existing != null) {
                statements.add("ALTER TABLE $fullTable DROP COLUMN \"${existing.name}\"")
                continue
            }

            if (existing == null && newName.isNotBlank()) {
                // New column
                val sb = StringBuilder()
                sb.append("ALTER TABLE $fullTable ADD COLUMN \"$newName\" ${colForm.type}")
                if (!colForm.nullable) sb.append(" NOT NULL")
                colForm.defaultValue?.takeIf { it.isNotBlank() }?.let {
                    sb.append(" DEFAULT $it")
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
                    val commentSql = newComment?.let {
                        "COMMENT ON COLUMN $fullTable.\"$effectiveName\" IS '${it.replace("'", "''")}'"
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

        if (statements.isEmpty()) {
            redirect.addFlashAttribute("message", "No changes to apply")
            return "redirect:/schemas?schema=$schema&table=$table"
        }

        // Handle new index creation (composite, unique, partial)
        val newIndexName = form.newIndexName?.trim().orEmpty()
        val newIndexColumns = form.newIndexColumns?.trim().orEmpty()
        val newIndexWhere = form.newIndexWhere?.trim().orEmpty()
        val newIndexUnique = form.newIndexUnique

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

        val sql = statements.joinToString(";\n") + ";"

        return try {
            repo.executeTableSql(sql)
            redirect.addFlashAttribute("message", "Table $schema.$table updated")
            "redirect:/schemas?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error updating table: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/index/delete")
    fun deleteIndex(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam indexName: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.dropIndex(schema, indexName)
            redirect.addFlashAttribute("message", "Index $schema.$indexName deleted")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error deleting index: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/delete")
    fun deleteTable(
        @RequestParam schema: String,
        @RequestParam table: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.dropTable(schema, table)
            redirect.addFlashAttribute("message", "Table $schema.$table deleted")
            "redirect:/schemas?schema=$schema"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error deleting table: ${ex.message}")
            "redirect:/schemas?schema=$schema&table=$table"
        }
    }

    @PostMapping("/table/create")
    fun createTable(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(defaultValue = "serial") idStrategy: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.createTable(schema, name, idStrategy)
            redirect.addFlashAttribute("message", "Table $schema.$name created")
            "redirect:/schemas?schema=$schema&table=$name"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error creating table: ${ex.message}")
            "redirect:/schemas?schema=$schema"
        }
    }
}
