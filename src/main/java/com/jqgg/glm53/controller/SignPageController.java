package com.jqgg.glm53.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 乙方签署页入口：直接访问 /sign/{token} 或刷新页面时转发到电脑端 Vue 应用，
 * 由前端路由渲染响应式签署页（手机浏览器同样可访问）。
 */
@Controller
public class SignPageController {

    @GetMapping("/sign/{token}")
    public String signPage() {
        return "forward:/index.html";
    }
}
