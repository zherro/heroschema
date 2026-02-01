package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class FunctionController(
    private val repo: MetadataRepository
) {

    @GetMapping("/functions")
    fun page(
        @RequestParam(required = false) schema: String?,
        @RequestParam(required = false) q: String?,
        model: Model
    ): String {
        val schemas = repo.listSchemas()
        val functions = repo.listFunctions(schema, q)

        model.addAttribute("page", "functions")
        model.addAttribute("pageTitle", "Functions")
        model.addAttribute("schemas", schemas)
        model.addAttribute("selectedSchema", schema)
        model.addAttribute("q", q)
        model.addAttribute("functions", functions)
        model.addAttribute("content", "fragments/functions-content")

        return "layout"
    }

    @GetMapping("/functions/new")
    fun newPage(model: Model): String {
        model.addAttribute("page", "functions")
        model.addAttribute("pageTitle", "New function")
        model.addAttribute("content", "fragments/functions-create")
        return "layout"
    }

    @GetMapping("/functions/edit")
    fun edit(
        @RequestParam schema: String,
        @RequestParam name: String,
        model: Model
    ): String {
        val fn = repo.listFunctions(schema, null).find { it.name == name }

        model.addAttribute("page", "functions")
        model.addAttribute("pageTitle", "Edit function $schema.$name")
        model.addAttribute("fn", fn)
        model.addAttribute("content", "fragments/functions-edit")

        return "layout"
    }

    @PostMapping("/functions/edit")
    fun save(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam definition: String,
        redirect: RedirectAttributes
    ): String {
        repo.applyFunctionDefinition(definition)
        redirect.addFlashAttribute("message", "Function $schema.$name updated")
        return "redirect:/functions?schema=$schema&q=$name"
    }

    @PostMapping("/functions/new")
    fun create(
        @RequestParam definition: String,
        redirect: RedirectAttributes
    ): String {
        repo.applyFunctionDefinition(definition)
        redirect.addFlashAttribute("message", "Function created")
        return "redirect:/functions"
    }

    @PostMapping("/functions/delete")
    fun delete(
        @RequestParam schema: String,
        @RequestParam name: String,
        redirect: RedirectAttributes
    ): String {
        repo.deleteFunction(schema, name)
        redirect.addFlashAttribute("message", "Function $schema.$name deleted")
        return "redirect:/functions?schema=$schema"
    }

    @GetMapping("/functions/download")
    fun download(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "false") noSchema: Boolean
    ): ResponseEntity<ByteArrayResource> {
        val fn = repo.listFunctions(schema, null).find { it.name == name }
            ?: return ResponseEntity.notFound().build()

        val original = fn.definition ?: ""
        val sql = if (noSchema) stripSchemaFromDefinition(schema, name, original) else original

        val filename = if (noSchema) "${name}.sql" else "${schema}_${name}.sql"
        val bytes = sql.toByteArray(Charsets.UTF_8)
        val resource = ByteArrayResource(bytes)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/sql"))
            .contentLength(bytes.size.toLong())
            .body(resource)
    }

    private fun stripSchemaFromDefinition(schema: String, name: String, definition: String): String {
        // Remove schema do cabeçalho da função
        val headerPattern = Regex(
            pattern = "(?i)(CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+)" +
                    Regex.escape(schema) + "\\." + Regex.escape(name)
        )
        var result = definition.replaceFirst(headerPattern, "${'$'}1$name")

        // Também remover prefixos de schema dentro do corpo, ex: public.users -> users
        val bodySchemaPattern = Regex(
            pattern = "(?i)\\b" + Regex.escape(schema) + "\\." + "([a-zA-Z_][a-zA-Z0-9_]*)"
        )
        result = result.replace(bodySchemaPattern, "${'$'}1")

        return result
    }
}
