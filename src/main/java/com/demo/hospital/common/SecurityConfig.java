package com.demo.hospital.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置。
 *
 * <p><b>T-001 阶段的临时版本</b>：只配 {@link PasswordEncoder} 与"全部放行"，
 * 目的是让骨架能起来、让 {@link DemoDataInitializer} 能算哈希。
 *
 * <p>JWT 过滤器与真实的鉴权规则在 <b>T-003</b> 实现——
 * 那时会把这个"全部放行"换掉。
 *
 * <p>⚠️ 明确标注"临时"，是为了防止它被遗忘在生产配置里。
 * 这类"先放行后面再收紧"的代码是最容易漏改的一类。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 口令编码器：BCrypt 自带盐、可调强度，是密码存储的稳妥默认值。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ⚠️ TODO(T-003)：这里现在是全放行，仅用于骨架阶段。
                //    T-003 会改为：/api/auth/** 与 /api/public/** 放行，其余必须认证。
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
