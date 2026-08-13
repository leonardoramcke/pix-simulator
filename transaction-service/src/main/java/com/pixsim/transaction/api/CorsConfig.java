package com.pixsim.transaction.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Libera CORS para o frontend de demonstração acessar a API a partir do
 * navegador (inclusive quando aberto via arquivo local, file://, que o
 * navegador trata como origem "null"). Em produção real, isso seria
 * restrito a domínios específicos — aqui, wildcard é aceitável pois é um
 * ambiente 100% local, sem autenticação por cookie/sessão.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
