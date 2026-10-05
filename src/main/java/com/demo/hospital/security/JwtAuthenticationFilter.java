package com.demo.hospital.security;

import com.demo.hospital.auth.domain.CurrentUser;
import com.demo.hospital.auth.jwt.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>职责只有三件：取令牌 → 校验 → 把身份放进上下文。
 * 它<b>不做授权判断</b>（那是 SecurityFilterChain 的事），
 * 也<b>不写错误响应</b>（那是 EntryPoint 的事）。
 *
 * <p>两个容易做错的地方，这里刻意都处理了（与项目 1 一致）：
 * <ol>
 *   <li><b>在 finally 中清理 ThreadLocal</b>：Tomcat 复用线程，不清理会导致
 *       下一个请求读到上一个用户的 id。</li>
 *   <li><b>令牌无效时不抛异常，而是带着"没有身份"继续往下走</b>：
 *       最后由 {@link RestAuthenticationEntryPoint} 统一返回 401。
 *       在这里抛异常会绕过 Spring Security 的异常处理链，导致 401 的响应体
 *       变成 HTML 错误页——前端就得写两套解析逻辑。</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticate(token, request);
            }
            chain.doFilter(request, response);
        } finally {
            // 必须清理：线程会被复用
            CurrentUser.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token, HttpServletRequest request) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(token);
        } catch (JwtService.InvalidTokenException e) {
            // 记录原因供 EntryPoint 区分"过期"与"伪造"，但不把细节返回给客户端
            request.setAttribute(AuthAttributes.FAILURE_REASON, e.getProblem().name());
            return;
        }

        CurrentUser.set(parsed.userId());

        // 本项目没有角色体系（只有"患者"一种身份），因此 authorities 为空列表。
        // 这不是遗漏：等到真的需要区分身份时再加，而不是现在预留一个空的 Role。
        var authentication = new UsernamePasswordAuthenticationToken(
                parsed.userId(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 请求属性名常量，避免字符串散落各处。 */
    public static final class AuthAttributes {

        public static final String FAILURE_REASON = "auth.failureReason";

        private AuthAttributes() {
        }
    }
}
