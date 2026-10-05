package com.demo.hospital.common;

import com.demo.hospital.security.JwtAuthenticationFilter;
import com.demo.hospital.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置。
 *
 * <p><b>T-003 已完成</b>：这里从"全部放行"的临时版本（T-001）换成了真实规则。
 * 那个临时版本带着 {@code TODO(T-003)} 标注留在仓库里，就是为了让这件事不可能被遗忘——
 * "先放行、后来又收紧"的代码是最容易漏改的一类。
 *
 * <p>设计取向是<b>默认拒绝</b>：{@code anyRequest().authenticated()} 收口，
 * 只把确实需要匿名的端点显式放开。反过来写（默认放行、逐个加保护）
 * 迟早会漏掉一个新增的接口——而漏掉的那个通常就是泄露数据的那个。
 *
 * <p>无状态（{@code STATELESS}）：身份完全来自 JWT，服务端不存会话。
 * 这也是后面能"多实例部署"的前提（本期虽然单机，但不给自己挖坑）。
 *
 * <p>刻意<b>不配表单登录与 HTTP Basic</b>：接口是给前端 JS 调的，
 * 留着它们只会多出两个可被探测的认证入口，而且它们的失败响应格式与我们的 JSON 不一致。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 免认证路径白名单。<b>新增公开接口必须在这里登记，否则默认被拦。</b>
     *
     * <p>为什么 {@code /api/auth/me} <b>不在</b>白名单里：
     * 它的语义就是"告诉我我是谁"，没有身份时不存在有意义的回答，
     * 返回 401 才是正确行为。
     *
     * <p>为什么放行 {@code /api/auth/register} 与 {@code /api/auth/login}：
     * 这两个动作发生时用户<b>还没有</b>令牌。这是白名单唯一正当的形态——
     * <b>不是为了方便，而是因为此刻没有可验证的身份。</b>
     */
    private static final String[] PUBLIC_PATHS = {
            // 登录与注册：此刻还没有令牌
            "/api/auth/register",
            "/api/auth/login",
            // 健康检查：给运维/探活用，不含业务数据
            "/api/health",
            // 错误页：Spring Boot 内部转发（/error）。不放行的话，
            // 任何未被捕获的错误会先被安全链拦成 401，把真实的 500 掩盖掉。
            "/error"
    };

    /** 口令编码器：BCrypt 自带盐、可调强度，是密码存储的稳妥默认值。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           RestAuthenticationEntryPoint entryPoint) throws Exception {
        http
                // 前后端分离 + JWT 放在 Authorization 头里，不使用 Cookie 携带身份，
                // 因此 CSRF 不适用（CSRF 攻击的前提是浏览器会自动带上凭据）。
                // ⚠️ 若将来改成 Cookie 存令牌，这里必须重新开启，否则就是 CSRF 漏洞。
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // Spring Security 自带的 /logout 是"清 session"语义，对无状态 JWT 无意义。
                // 关掉它是为了不让人误以为存在一个可用的登出端点（本期的登出在前端删令牌即可，
                // 服务端黑名单见 docs/02 的说明）。
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 统一 401 响应体格式：默认行为会返回 HTML 错误页，前端无法解析
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求必须放行，否则浏览器跨域调用会在 OPTIONS 阶段就被拦掉
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 默认拒绝：上面没登记的，一律要求认证
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
