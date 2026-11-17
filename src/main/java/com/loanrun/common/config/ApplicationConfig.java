package com.loanrun.common.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApplicationConfig implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public ApplicationConfig(@Value("${loanrun.allowed-origins}") String origins) {
        allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(s -> s.contains("*"))) {
            throw new IllegalArgumentException("Exact ALLOWED_ORIGINS is required");
        }
    }

    public List<String> allowedOrigins() { return allowedOrigins; }

    @Bean
    Clock businessClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName("LOANRUN_SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        cookie.setCookieMaxAge(7 * 24 * 60 * 60);
        return cookie;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.postConfigurer(mapper -> {
            for (LogicalType type : List.of(LogicalType.Integer, LogicalType.Float, LogicalType.Boolean)) {
                mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            }
        });
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Idempotency-Key").allowCredentials(true);
    }
}
