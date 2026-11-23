package com.example.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

/**
 * MVP 鉴权：HTTP Basic + 三个内存账号（admin/editor/viewer）。
 *
 * <p>注意：真正的类型级/属性级判断不在这里，而在 {@link com.example.ontology.security.OntologyAuthorizer}
 * —— 因为权限要按 type / prop 动态求值，注解表达不了。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.withUsername("admin").password(encoder.encode("admin")).roles("ADMIN").build();
        UserDetails editor = User.withUsername("editor").password(encoder.encode("editor")).roles("EDITOR").build();
        UserDetails viewer = User.withUsername("viewer").password(encoder.encode("viewer")).roles("VIEWER").build();
        return new InMemoryUserDetailsManager(admin, editor, viewer);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 演示控制台的静态外壳：放行页面本身，所有数据请求仍走 /api/** 的认证
                        .requestMatchers("/", "/index.html", "/*.js", "/*.css", "/views/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, mapper, 401,
                                "UNAUTHORIZED", "未认证：请使用 HTTP Basic 提供账号密码"))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, mapper, 403,
                                "FORBIDDEN", "无权限")));
        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                            String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Map.of(
                "code", code,
                "message", message,
                "path", "",
                "timestamp", java.time.Instant.now().toString()));
    }
}
