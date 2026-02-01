package com.br.bettersoft.heroschema.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController {

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("page", "home")
        model.addAttribute("pageTitle", "Bem-vindo ao HeroSchema")
        model.addAttribute("content", "fragments/content")
        return "layout"
    }
}
