package com.br.bettersoft.heroschema.controller

import com.br.bettersoft.heroschema.service.AuthScriptsService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/auth")
class AuthController(
    private val authScriptsService: AuthScriptsService
) {

    @GetMapping
    fun page(model: Model): String {
        val scripts = authScriptsService.listScripts()
        val status = authScriptsService.getStatus()

        model.addAttribute("page", "auth")
        model.addAttribute("pageTitle", "Auth / Multi-tenant setup")
        model.addAttribute("scripts", scripts)
        model.addAttribute("authStatus", status)
        model.addAttribute("content", "fragments/auth-content")

        return "layout"
    }

    @PostMapping("/install")
    fun install(
        @RequestParam(name = "fromStart", defaultValue = "false") fromStart: Boolean,
        redirect: RedirectAttributes
    ): String {
        val result = if (fromStart) {
            authScriptsService.resetAndRunAll()
        } else {
            authScriptsService.runAll()
        }

        if (result.errors.isEmpty()) {
            val msg = if (result.executed.isEmpty()) {
                "Nenhum script executado. Auth system já estava instalado (ou não há scripts novos)."
            } else {
                if (fromStart) {
                    "Reinstalação do auth system concluída com ${result.executed.size} scripts."
                } else {
                    "Auth system instalado/atualizado com ${result.executed.size} scripts."
                }
            }
            redirect.addFlashAttribute("message", msg)
        } else {
            val msg = buildString {
                append("Instalação do auth system terminou com erros. Executados ")
                append(result.executed.size)
                append(" scripts. Primeiro erro: ")
                append(result.errors.first().message)
            }
            redirect.addFlashAttribute("error", msg)
        }

        return "redirect:/auth"
    }
}
