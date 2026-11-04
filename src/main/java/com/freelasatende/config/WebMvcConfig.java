package com.freelasatende.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/**
 * Configuração do ViewResolver para Thymeleaf.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ControleApiAuthInterceptor controleApiAuthInterceptor;

    public WebMvcConfig(ControleApiAuthInterceptor controleApiAuthInterceptor) {
        this.controleApiAuthInterceptor = controleApiAuthInterceptor;
    }

    @Override
    public void configureViewResolvers(ViewResolverRegistry registry) {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("classpath:/templates/");
        resolver.setSuffix(".html");
        registry.viewResolver(resolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(controleApiAuthInterceptor)
            .addPathPatterns("/api/controle/**", "/api/modos/**", "/api/ml-lab/**");
    }
}
