package com.br.bettersoft.heroschema.controller

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AboutController {

    @GetMapping("/about")
    fun about(model: Model): String {
        model.addAttribute("page", "about")
        model.addAttribute("pageTitle", "Sobre o HeroSchema")
        model.addAttribute("content", "fragments/about-content")
        return "layout"
    }
}
