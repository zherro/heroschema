package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.dtos.ColumnFormDto
import com.br.bettersoft.heroschema.dtos.ColumnWithConstraintsDto
import com.br.bettersoft.heroschema.dtos.SchemaWithTablesDto
import com.br.bettersoft.heroschema.dtos.TableEditFormDto
import com.br.bettersoft.heroschema.dtos.TablePermissionsFormDto
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
        val roles = repo.listRoles()
        val indexes = repo.listIndexes(schema, table)
        val policies = repo.listPolicies(schema, table)
        val tableGrants = repo.listTableGrants(schema, table)
        val rlsEnabled = repo.isRlsEnabled(schema, table)

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

        val permissionsForm = TablePermissionsFormDto(
            schema = schema,
            table = table,
            sequenceName = "${table}_id_seq",
            resourceName = table,
            policyName = "select_$table",
            policyPreset = "custom",
            policyAction = "select",
            policyEditorSql = "-- Fill USING and/or WITH CHECK"
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
        model.addAttribute("roleOptions", roles)
        model.addAttribute("indexes", indexes)
        model.addAttribute("policies", policies)
        model.addAttribute("tableGrants", tableGrants)
        model.addAttribute("rlsEnabled", rlsEnabled)
        if (!model.containsAttribute("permissionsForm")) {
            model.addAttribute("permissionsForm", permissionsForm)
        }
        model.addAttribute("content", "fragments/table-edit")
        return "layout"
    }

    // --- JSON helpers for FK selects (searchable schema/table/column) ---

    @GetMapping("/fk/schemas", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun fkSchemas(): List<String> =
        repo.listSchemas()

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

    @PostMapping("/permissions/preview")
    fun previewPermissions(
        @ModelAttribute permissionsForm: TablePermissionsFormDto,
        redirect: RedirectAttributes
    ): String {
        val schema = permissionsForm.schema
        val table = permissionsForm.table

        return try {
            val sql = buildPermissionsSql(permissionsForm)
            redirect.addFlashAttribute("permissionSqlPreview", sql)
            redirect.addFlashAttribute("message", "Permissions SQL generated")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error generating permissions SQL: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/permissions/apply")
    fun applyPermissions(
        @ModelAttribute permissionsForm: TablePermissionsFormDto,
        redirect: RedirectAttributes
    ): String {
        val schema = permissionsForm.schema
        val table = permissionsForm.table

        return try {
            val sql = buildPermissionsSql(permissionsForm)
            repo.executeTableSql(sql)
            redirect.addFlashAttribute("permissionSqlPreview", sql)
            redirect.addFlashAttribute("message", "Permissions updated for $schema.$table")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error applying permissions: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/grant/table/apply")
    fun applyTableGrant(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam grantee: String,
        @RequestParam(defaultValue = "false") grantSelect: Boolean,
        @RequestParam(defaultValue = "false") grantInsert: Boolean,
        @RequestParam(defaultValue = "false") grantUpdate: Boolean,
        @RequestParam(defaultValue = "false") grantDelete: Boolean,
        redirect: RedirectAttributes
    ): String {
        val privileges = mutableListOf<String>()
        if (grantSelect) privileges.add("SELECT")
        if (grantInsert) privileges.add("INSERT")
        if (grantUpdate) privileges.add("UPDATE")
        if (grantDelete) privileges.add("DELETE")

        if (grantee.isBlank() || privileges.isEmpty()) {
            redirect.addFlashAttribute("error", "Select role and at least one privilege")
            return "redirect:/schemas/edit?schema=$schema&table=$table"
        }

        return try {
            repo.grantTablePrivileges(schema, table, grantee, privileges)
            redirect.addFlashAttribute("message", "Granted ${privileges.joinToString(",")} on $schema.$table to $grantee")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error applying grant: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/grant/table/revoke")
    fun revokeTableGrant(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam grantee: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.revokeAllTablePrivileges(schema, table, grantee)
            redirect.addFlashAttribute("message", "Revoked table privileges from $grantee")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error revoking grant: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/rls/enable")
    fun enableRls(
        @RequestParam schema: String,
        @RequestParam table: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.enableRls(schema, table)
            redirect.addFlashAttribute("message", "RLS enabled for $schema.$table")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error enabling RLS: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/rls/disable")
    fun disableRls(
        @RequestParam schema: String,
        @RequestParam table: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.disableRls(schema, table)
            redirect.addFlashAttribute("message", "RLS disabled for $schema.$table")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error disabling RLS: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/policy/create/editor")
    fun createPolicy(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam policySql: String,
        redirect: RedirectAttributes
    ): String {
        if (!repo.isRlsEnabled(schema, table)) {
            redirect.addFlashAttribute("error", "Enable RLS before creating policies")
            return "redirect:/schemas/edit?schema=$schema&table=$table"
        }

        return try {
            val normalizedSql = policySql.trim().let { if (it.endsWith(";")) it else "$it;" }
            if (normalizedSql.isBlank()) {
                throw IllegalArgumentException("Policy SQL cannot be empty")
            }
            repo.executeTableSql(normalizedSql)
            redirect.addFlashAttribute("policySqlPreview", normalizedSql)
            redirect.addFlashAttribute("message", "Policy created")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error creating policy: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/policy/editor/preview")
    fun previewPolicyEditor(
        @ModelAttribute permissionsForm: TablePermissionsFormDto,
        @RequestParam(defaultValue = "single") mode: String,
        redirect: RedirectAttributes
    ): String {
        val schema = permissionsForm.schema
        val table = permissionsForm.table

        if (!repo.isRlsEnabled(schema, table)) {
            redirect.addFlashAttribute("error", "Enable RLS before creating policies")
            return "redirect:/schemas/edit?schema=$schema&table=$table"
        }

        return try {
            val builtSql = if (mode == "combo") {
                buildComboPoliciesSql(permissionsForm)
            } else {
                buildPolicyByActionSql(permissionsForm, permissionsForm.policyAction)
            }

            val existing = permissionsForm.policyEditorSql?.trim().orEmpty()
                .let { if (it == "-- Fill USING and/or WITH CHECK") "" else it }
            val merged = if (existing.isBlank()) builtSql else "$existing\n\n$builtSql"
            permissionsForm.policyEditorSql = merged

            redirect.addFlashAttribute("policySqlPreview", builtSql)
            redirect.addFlashAttribute("permissionsForm", permissionsForm)
            redirect.addFlashAttribute("message", "Policy SQL added to editor")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("permissionsForm", permissionsForm)
            redirect.addFlashAttribute("error", "Error building policy SQL: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/policy/editor/apply")
    fun applyPolicyEditor(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam policyEditorSql: String,
        redirect: RedirectAttributes
    ): String {
        if (!repo.isRlsEnabled(schema, table)) {
            redirect.addFlashAttribute("error", "Enable RLS before applying policies")
            return "redirect:/schemas/edit?schema=$schema&table=$table"
        }

        return try {
            val normalizedSql = policyEditorSql.trim()
            if (normalizedSql.isBlank()) {
                throw IllegalArgumentException("Policy editor is empty")
            }

            // Execute exactly what user edited in the SQL editor.
            repo.executeTableSql(normalizedSql)
            redirect.addFlashAttribute("policySqlPreview", normalizedSql)
            redirect.addFlashAttribute("message", "Policy SQL applied")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("policySqlPreview", policyEditorSql)
            redirect.addFlashAttribute("error", "Error applying policy SQL: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/policy/delete")
    fun deletePolicy(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam policyName: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            repo.dropPolicy(schema, table, policyName)
            redirect.addFlashAttribute("message", "Policy $policyName removed")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error removing policy: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    @PostMapping("/policy/update")
    fun updatePolicy(
        @RequestParam schema: String,
        @RequestParam table: String,
        @RequestParam policyName: String,
        @RequestParam policySql: String,
        redirect: RedirectAttributes
    ): String {
        return try {
            val normalizedSql = policySql.trim().let { if (it.endsWith(";")) it else "$it;" }
            val sql = "DROP POLICY IF EXISTS \"$policyName\" ON \"$schema\".\"$table\";\n$normalizedSql"
            repo.executeTableSql(sql)
            redirect.addFlashAttribute("message", "Policy $policyName updated")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error updating policy: ${ex.message}")
            "redirect:/schemas/edit?schema=$schema&table=$table"
        }
    }

    private fun buildPermissionsSql(form: TablePermissionsFormDto): String {
        val schema = form.schema
        val table = form.table
        val fullTable = "$schema.$table"
        val stmts = mutableListOf<String>()

        if (form.grantAnonSelect) {
            stmts.add("GRANT SELECT ON TABLE $fullTable TO anon")
        }

        val authUserGrants = mutableListOf<String>()
        if (form.grantAuthUserSelect) authUserGrants.add("SELECT")
        if (form.grantAuthUserInsert) authUserGrants.add("INSERT")
        if (form.grantAuthUserDelete) authUserGrants.add("DELETE")
        if (form.grantAuthUserUpdate) authUserGrants.add("UPDATE")
        if (authUserGrants.isNotEmpty()) {
            stmts.add("GRANT ${authUserGrants.joinToString(",")} ON TABLE $fullTable TO auth_user")
        }

        val seqName = form.sequenceName?.trim().orEmpty()
        if (seqName.isNotEmpty()) {
            val seqGrants = mutableListOf<String>()
            if (form.grantSequenceUsage) seqGrants.add("USAGE")
            if (form.grantSequenceSelect) seqGrants.add("SELECT")
            if (seqGrants.isNotEmpty()) {
                stmts.add("GRANT ${seqGrants.joinToString(", ")} ON SEQUENCE $schema.$seqName TO auth_user")
            }
        }

        if (stmts.isEmpty()) {
            return "-- No grants selected"
        }

        return stmts.joinToString(";\n") + ";"
    }

    private fun buildComboPoliciesSql(form: TablePermissionsFormDto): String {
        val actions = linkedSetOf<String>()
        actions.add(form.policyAction.lowercase())
        if (form.comboSelect) actions.add("select")
        if (form.comboInsert) actions.add("insert")
        if (form.comboUpdate) actions.add("update")
        if (form.comboDelete) actions.add("delete")

        if (actions.isEmpty()) {
            throw IllegalArgumentException("Select at least one action for combo")
        }

        val basePolicyName = form.policyName.trim()
        if (basePolicyName.isBlank()) {
            throw IllegalArgumentException("Policy name is required")
        }
        val roleSlug = form.policyRole.ifBlank { "auth_user" }.lowercase().replace(" ", "_")
        val tableName = form.table

        val sqlParts = actions.map { action ->
            val actionPolicyName = if (actions.size == 1) {
                basePolicyName
            } else {
                "${action}_${tableName}_${roleSlug}"
            }
            buildPolicyByActionSql(form, action, actionPolicyName)
        }

        return "-- Selected actions: ${actions.joinToString(", ")}" + "\n" + sqlParts.joinToString("\n\n")
    }

    private fun buildPolicyByActionSql(
        form: TablePermissionsFormDto,
        actionRaw: String,
        policyNameOverride: String? = null
    ): String {
        val schema = form.schema
        val table = form.table
        val action = actionRaw.lowercase()
        val role = form.policyRole.ifBlank { "auth_user" }

        val policyName = policyNameOverride?.trim().orEmpty().ifBlank { form.policyName.trim() }
        val fullTable = "$schema.$table"

        if (policyName.isBlank()) {
            throw IllegalArgumentException("Policy name is required")
        }

        if (form.policyPreset != "custom") {
            throw IllegalArgumentException("Use custom mode")
        }

        val customUsing = form.customUsingExpr?.trim().orEmpty()
        val customWithCheck = form.customWithCheckExpr?.trim().orEmpty()
        if (customUsing.isBlank() && customWithCheck.isBlank()) {
            throw IllegalArgumentException("For custom mode, fill USING and/or WITH CHECK")
        }

        val sb = StringBuilder()
        sb.append("CREATE POLICY $policyName ON $fullTable\n")
            .append("  FOR ${action.uppercase()} TO $role")

        when (action) {
            "insert" -> {
                if (customWithCheck.isNotBlank()) sb.append(" WITH CHECK ($customWithCheck)")
            }
            "update" -> {
                if (customUsing.isNotBlank()) sb.append(" USING ($customUsing)")
                if (customWithCheck.isNotBlank()) sb.append(" WITH CHECK ($customWithCheck)")
            }
            else -> {
                if (customUsing.isNotBlank()) sb.append(" USING ($customUsing)")
            }
        }
        sb.append(";")
        return sb.toString()
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
