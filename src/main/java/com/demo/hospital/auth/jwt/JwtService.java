package com.demo.hospital.auth.jwt;

import com.demo.hospital.auth.domain.IssuedToken;
import com.demo.hospital.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 的签发与解析。
 *
 * <p>做法直接沿用项目 1（决策 D-09：<b>已验证过的东西不重新发明</b>），
 * 但载荷按本项目的实际需要做了精简。
 *
 * <p><b>载荷里放什么、不放什么</b>：
 * <ul>
 *   <li>放：{@code sub}=用户 id、{@code iss}、{@code iat}、{@code exp}、{@code jti}</li>
 *   <li><b>不放</b>：口令哈希、证件号，以及任何"用户改了一下就会过时"的字段</li>
 * </ul>
 *
 * <p>⚠️ 关键认知：JWT 只是<b>签名</b>，不是<b>加密</b>——任何人都能解开看内容。
 * 所以"把手机号放进 token"并不等于保护了它，只是让它可以被看见。
 * 本项目因此只放 {@code sub}：需要用户信息时去数据库读（见 {@code AuthService#currentUser}），
 * 这样"账号被停用"这类变更能立刻生效，而不是等令牌自然过期。
 */
@Component
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发令牌。
     *
     * @param userId 用户 id，作为 {@code sub}
     */
    public IssuedToken issue(Long userId) {
        Instant now = Instant.now();
        long expiresIn = properties.expireMinutes() * 60;
        Instant exp = now.plusSeconds(expiresIn);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(properties.issuer())
                // jti：令牌的唯一标识。当前未用于登出黑名单，但"没有 jti"的令牌
                // 事后无法被单独作废——补一个字段的成本是零，缺了却要改所有已发的令牌。
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, exp, expiresIn);
    }

    /**
     * 校验并解析令牌。
     *
     * <p>失败一律抛 {@link InvalidTokenException} 并带上原因分类：
     * "过期"与"签名不对"对用户的意义完全不同——前者引导重新登录，后者可能意味着有人在伪造令牌。
     */
    public ParsedToken parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new ParsedToken(
                    Long.valueOf(c.getSubject()),
                    c.getId(),
                    c.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(TokenProblem.EXPIRED, "令牌已过期", e);
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不符、结构损坏、issuer 不匹配、sub 不是数字，都落在这里
            throw new InvalidTokenException(TokenProblem.MALFORMED, "令牌无效", e);
        }
    }

    /** 令牌解析失败的原因分类。 */
    public enum TokenProblem {
        /** 已过期——属于正常生命周期，前端应引导重新登录。 */
        EXPIRED,
        /** 签名/结构/issuer 不合法——属于异常情况，值得记日志。 */
        MALFORMED
    }

    public static class InvalidTokenException extends RuntimeException {

        private final TokenProblem problem;

        public InvalidTokenException(TokenProblem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }

        public TokenProblem getProblem() {
            return problem;
        }
    }

    /**
     * 解析结果。
     *
     * @param jti 令牌唯一标识（当前仅记录，为将来的登出黑名单留出位置）
     */
    public record ParsedToken(Long userId, String jti, Instant expiresAt) {
    }
}
