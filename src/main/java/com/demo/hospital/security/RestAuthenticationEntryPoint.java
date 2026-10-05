package com.demo.hospital.security;

import com.demo.hospital.auth.AuthErrorCode;
import com.demo.hospital.auth.jwt.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未认证请求的 401 响应。
 *
 * <p>存在的唯一理由是<b>让所有失败路径返回同一种响应格式</b>
 * （与 {@code GlobalExceptionHandler} 的 {@code {code,message,path,time}} 对齐）。
 *
 * <p>不写这个类会怎样：没带令牌的请求会被 Spring Security 返回一个 HTML 错误页，
 * 而令牌过期的请求走到另一条路径——前端必须写两套解析逻辑，
 * 而且第一套还只能靠"看返回的是不是 HTML"来判断。
 *
 * <p>这里还会根据过滤器留下的失败原因区分 {@code TOKEN_EXPIRED} 与 {@code TOKEN_INVALID}：
 * 前者是正常生命周期（引导重新登录），后者可能意味着有人在伪造令牌（值得记日志）。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object reason = request.getAttribute(JwtAuthenticationFilter.AuthAttributes.FAILURE_REASON);

        AuthErrorCode code;
        String message;
        if (JwtService.TokenProblem.EXPIRED.name().equals(reason)) {
            code = AuthErrorCode.TOKEN_EXPIRED;
            message = "登录已过期，请重新登录";
        } else if (JwtService.TokenProblem.MALFORMED.name().equals(reason)) {
            code = AuthErrorCode.TOKEN_INVALID;
            message = "令牌无效";
        } else {
            code = AuthErrorCode.UNAUTHENTICATED;
            message = "请先登录";
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("time", OffsetDateTime.now().toString());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
