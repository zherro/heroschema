package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.dtos.SqlTerminalRequestDto
import com.br.bettersoft.heroschema.dtos.SqlTerminalResponseDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import com.br.bettersoft.heroschema.service.SqlTerminalService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@Controller
@RequestMapping("/sql-terminal")
class SqlTerminalController(
    private val repo: MetadataRepository,
    private val sqlTerminalService: SqlTerminalService
) {

    @GetMapping
    fun page(
        @RequestParam(required = false) schema: String?,
        model: Model
    ): String {
        val schemas = repo.listSchemas()
        val selectedSchema = schema?.takeIf { schemas.contains(it) } ?: schemas.firstOrNull().orEmpty()

        model.addAttribute("page", "sql-terminal")
        model.addAttribute("pageTitle", "SQL Terminal")
        model.addAttribute("schemas", schemas)
        model.addAttribute("selectedSchema", selectedSchema)
        model.addAttribute("content", "sql-terminal")
        return "layout"
    }

    @PostMapping("/execute", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun execute(
        @RequestBody req: SqlTerminalRequestDto
    ): ResponseEntity<SqlTerminalResponseDto> {
        return try {
            val response = sqlTerminalService.execute(req.schema, req.sql, req.maxRows ?: 200)
            ResponseEntity.ok(response)
        } catch (ex: Exception) {
            ResponseEntity.badRequest().body(
                SqlTerminalResponseDto(
                    schema = req.schema,
                    ok = false,
                    statements = emptyList(),
                    error = ex.message ?: "Error executing SQL"
                )
            )
        }
    }

    @GetMapping("/hints", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun hints(@RequestParam schema: String) = sqlTerminalService.hints(schema)
}

