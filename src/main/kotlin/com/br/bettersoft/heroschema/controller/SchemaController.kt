package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.dtos.SchemaWithTablesDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

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
            model.addAttribute(
                "columns",
                repo.listColumns(schema, table)
            )
            model.addAttribute("pageTitle", "$schema.$table")
        } else {
            model.addAttribute("pageTitle", "Schemas / Tables")
        }

        model.addAttribute("sidebar", "fragments/schemas-sidebar")
        model.addAttribute("content", "fragments/schemas-content")

        return "layout"
    }
}
