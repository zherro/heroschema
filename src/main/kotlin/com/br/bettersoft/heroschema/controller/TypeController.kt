package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.dtos.DomainTypeEditFormDto
import com.br.bettersoft.heroschema.dtos.DomainTypeFormDto
import com.br.bettersoft.heroschema.dtos.EnumTypeEditFormDto
import com.br.bettersoft.heroschema.dtos.EnumTypeFormDto
import com.br.bettersoft.heroschema.repository.MetadataRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/types")
class TypeController(
    private val repo: MetadataRepository
) {

    @GetMapping
    fun page(
        @RequestParam(required = false) schema: String?,
        model: Model
    ): String {
        val filter = schema?.takeIf { it.isNotBlank() }
        model.addAttribute("page", "types")
        model.addAttribute("pageTitle", "Custom types (enum / domain)")
        model.addAttribute("schemas", repo.listSchemas())
        model.addAttribute("selectedSchema", filter)
        model.addAttribute("enumTypes", repo.listEnumTypes(filter))
        model.addAttribute("domainTypes", repo.listDomainTypes(filter))
        model.addAttribute("content", "fragments/types-content")
        return "layout"
    }

    // --- ENUM ---

    @GetMapping("/enum/new")
    fun newEnumPage(model: Model): String {
        model.addAttribute("page", "types")
        model.addAttribute("pageTitle", "New enum type")
        model.addAttribute("schemas", repo.listSchemas())
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", EnumTypeFormDto())
        }
        model.addAttribute("content", "fragments/types-enum-create")
        return "layout"
    }

    @PostMapping("/enum/new")
    fun createEnum(
        @ModelAttribute form: EnumTypeFormDto,
        redirect: RedirectAttributes
    ): String {
        val values = form.values.map { it.trim() }.filter { it.isNotEmpty() }
        return try {
            repo.createEnumType(form.schema, form.name, values, form.comment)
            redirect.addFlashAttribute("message", "Enum type ${form.schema}.${form.name} created")
            "redirect:/types"
        } catch (ex: Exception) {
            redirect.addFlashAttribute("error", "Error creating enum type: ${ex.message}")
            "redirect:/types/enum/new"
        }
    }

    @GetMapping("/enum/edit")
    fun editEnumPage(
        @RequestParam schema: String,
        @RequestParam name: String,
        model: Model
    ): String {
        val enumType = repo.listEnumTypes(schema).find { it.name == name }

        model.addAttribute("page", "types")
        model.addAttribute("pageTitle", "Edit enum $schema.$name")
        model.addAttribute("enumType", enumType)
        if (!model.containsAttribute("form")) {
            model.addAttribute(
                "form",
                EnumTypeEditFormDto(schema = schema, name = name, comment = enumType?.comment)
            )
        }
        model.addAttribute("content", "fragments/types-enum-edit")
        return "layout"
    }

    @PostMapping("/enum/rename")
    fun renameEnum(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam newTypeName: String,
        redirect: RedirectAttributes
    ): String = try {
        repo.renameEnumType(schema, name, newTypeName)
        redirect.addFlashAttribute("message", "Enum type renamed to $newTypeName")
        "redirect:/types/enum/edit?schema=$schema&name=$newTypeName"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error renaming enum type: ${ex.message}")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    }

    @PostMapping("/enum/comment")
    fun commentEnum(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(required = false) comment: String?,
        redirect: RedirectAttributes
    ): String = try {
        repo.setTypeComment(schema, name, comment ?: "")
        redirect.addFlashAttribute("message", "Comment updated")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error updating comment: ${ex.message}")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    }

    @PostMapping("/enum/value/add")
    fun addEnumValue(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam newValue: String,
        @RequestParam(defaultValue = "end") insertPosition: String,
        @RequestParam(required = false) relativeToValue: String?,
        redirect: RedirectAttributes
    ): String = try {
        repo.addEnumValue(schema, name, newValue, insertPosition, relativeToValue)
        redirect.addFlashAttribute("message", "Value '$newValue' added to $schema.$name")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error adding value: ${ex.message}")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    }

    @PostMapping("/enum/value/rename")
    fun renameEnumValue(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam renameFrom: String,
        @RequestParam renameTo: String,
        redirect: RedirectAttributes
    ): String = try {
        repo.renameEnumValue(schema, name, renameFrom, renameTo)
        redirect.addFlashAttribute("message", "Value '$renameFrom' renamed to '$renameTo'")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error renaming value: ${ex.message}")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    }

    @PostMapping("/enum/delete")
    fun deleteEnum(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(defaultValue = "false") cascade: Boolean,
        redirect: RedirectAttributes
    ): String = try {
        repo.dropEnumType(schema, name, cascade)
        redirect.addFlashAttribute("message", "Enum type $schema.$name deleted")
        "redirect:/types"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error deleting enum type: ${ex.message}")
        "redirect:/types/enum/edit?schema=$schema&name=$name"
    }

    // --- DOMAIN ---

    @GetMapping("/domain/new")
    fun newDomainPage(model: Model): String {
        model.addAttribute("page", "types")
        model.addAttribute("pageTitle", "New domain type")
        model.addAttribute("schemas", repo.listSchemas())
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", DomainTypeFormDto())
        }
        model.addAttribute("content", "fragments/types-domain-create")
        return "layout"
    }

    @PostMapping("/domain/new")
    fun createDomain(
        @ModelAttribute form: DomainTypeFormDto,
        redirect: RedirectAttributes
    ): String = try {
        repo.createDomainType(
            form.schema, form.name, form.baseType, form.notNull,
            form.defaultValue, form.checkExpr, form.comment
        )
        redirect.addFlashAttribute("message", "Domain type ${form.schema}.${form.name} created")
        "redirect:/types"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error creating domain type: ${ex.message}")
        "redirect:/types/domain/new"
    }

    @GetMapping("/domain/edit")
    fun editDomainPage(
        @RequestParam schema: String,
        @RequestParam name: String,
        model: Model
    ): String {
        val domainType = repo.listDomainTypes(schema).find { it.name == name }

        model.addAttribute("page", "types")
        model.addAttribute("pageTitle", "Edit domain $schema.$name")
        model.addAttribute("domainType", domainType)
        if (!model.containsAttribute("form")) {
            model.addAttribute(
                "form",
                DomainTypeEditFormDto(
                    schema = schema,
                    name = name,
                    notNull = domainType?.notNull ?: false,
                    defaultValue = domainType?.defaultValue,
                    checkExpr = domainType?.checkExpr,
                    comment = domainType?.comment
                )
            )
        }
        model.addAttribute("content", "fragments/types-domain-edit")
        return "layout"
    }

    @PostMapping("/domain/rename")
    fun renameDomain(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam newTypeName: String,
        redirect: RedirectAttributes
    ): String = try {
        repo.renameDomain(schema, name, newTypeName)
        redirect.addFlashAttribute("message", "Domain type renamed to $newTypeName")
        "redirect:/types/domain/edit?schema=$schema&name=$newTypeName"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error renaming domain type: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }

    @PostMapping("/domain/default")
    fun setDomainDefault(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(required = false) defaultValue: String?,
        redirect: RedirectAttributes
    ): String = try {
        repo.alterDomainDefault(schema, name, defaultValue)
        redirect.addFlashAttribute("message", "Default updated")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error updating default: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }

    @PostMapping("/domain/notnull")
    fun setDomainNotNull(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(defaultValue = "false") notNull: Boolean,
        redirect: RedirectAttributes
    ): String = try {
        repo.alterDomainNotNull(schema, name, notNull)
        redirect.addFlashAttribute("message", "Not-null constraint updated")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error updating not-null constraint: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }

    @PostMapping("/domain/check")
    fun setDomainCheck(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(required = false) oldCheckName: String?,
        @RequestParam(required = false) newCheckExpr: String?,
        redirect: RedirectAttributes
    ): String = try {
        repo.alterDomainCheck(schema, name, oldCheckName, newCheckExpr)
        redirect.addFlashAttribute("message", "Check constraint updated")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error updating check constraint: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }

    @PostMapping("/domain/comment")
    fun commentDomain(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(required = false) comment: String?,
        redirect: RedirectAttributes
    ): String = try {
        repo.setDomainComment(schema, name, comment ?: "")
        redirect.addFlashAttribute("message", "Comment updated")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error updating comment: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }

    @PostMapping("/domain/delete")
    fun deleteDomain(
        @RequestParam schema: String,
        @RequestParam name: String,
        @RequestParam(defaultValue = "false") cascade: Boolean,
        redirect: RedirectAttributes
    ): String = try {
        repo.dropDomain(schema, name, cascade)
        redirect.addFlashAttribute("message", "Domain type $schema.$name deleted")
        "redirect:/types"
    } catch (ex: Exception) {
        redirect.addFlashAttribute("error", "Error deleting domain type: ${ex.message}")
        "redirect:/types/domain/edit?schema=$schema&name=$name"
    }
}
