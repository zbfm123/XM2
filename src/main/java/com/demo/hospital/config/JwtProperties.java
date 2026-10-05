package com.demo.hospital.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录与 JWT 相关配置，前缀 {@code app.jwt}。
 *
 * <p>沿用项目 1 的做法（决策 D-09）：<b>构造器里就把不合法的配置拦下来</b>。
 * 一个 {@code record} 加几行校验，换来的是"配置错了在启动时就知道"，
 * 而不是等到某个请求进来才抛一个难懂的加密库异常。
 *
 * @param secret        签名密钥，<b>长度至少 32 字节</b>（HS256 的要求）
 * @param issuer        签发者标识，解析时会校验
 * @param expireMinutes 令牌有效期（分钟）
 * @param maxFailures   连续失败多少次后锁定账号
 * @param lockMinutes   锁定时长（分钟）
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        long expireMinutes,
        int maxFailures,
        long lockMinutes
) {

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            // 刻意"显式失败"而不是给一个默认密钥：
            // 一个可预测的默认密钥意味着任何人都能自己签一个令牌冒充任意用户，
            // 而这种配置错误在开发机上永远不会被发现。
            throw new IllegalArgumentException(
                    "app.jwt.secret 至少需要 32 字节（HS256 要求）。当前长度="
                            + (secret == null ? 0 : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                            + "；请设置环境变量 JWT_SECRET");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("app.jwt.issuer 不能为空");
        }
        if (expireMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.expire-minutes 必须大于 0");
        }
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("app.jwt.max-failures 必须大于 0");
        }
        if (lockMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.lock-minutes 必须大于 0");
        }
    }
}
