package br.com.balaio.profile.account.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AutenticacaoWebConfig implements WebMvcConfigurer {
    private final OriginAutenticacaoInterceptor origin;

    public AutenticacaoWebConfig(OriginAutenticacaoInterceptor origin) {
        this.origin = origin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(origin).addPathPatterns("/api/v1/auth/cadastro", "/api/v1/auth/login",
                "/api/v1/auth/entrar", "/api/v1/auth/logout", "/api/v1/auth/recuperacao",
                "/api/v1/auth/redefinicao","/api/v1/auth/alterar-senha", "/api/v1/artesao/me/imagem",
                "/api/v1/usuario", "/api/v1/conta/*", "/api/v1/artesaos/*/configuracoes",
                "/api/v1/artesao/selo", "/api/v1/admin/curadoria/**");
    }
}
