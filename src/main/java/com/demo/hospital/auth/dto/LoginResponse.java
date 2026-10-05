package com.demo.hospital.auth.dto;

/**
 * 登录成功响应。
 *
 * <p>{@code expiresIn} / {@code expiresAt} 一并返回，是为了让前端能主动在过期前
 * 引导重新登录，而不是等请求被 401 打断——<b>把"会话快过期"变成一个可提前处理的普通状态。</b>
 *
 * @param token     JWT
 * @param tokenType 固定 Bearer，便于前端直接拼 {@code Authorization} 头
 * @param expiresIn 有效秒数
 * @param expiresAt 过期时刻（epoch 秒）
 * @param user      当前用户信息
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        long expiresAt,
        UserView user
) {

    public static LoginResponse bearer(String token, long expiresIn, long expiresAt, UserView user) {
        return new LoginResponse(token, "Bearer", expiresIn, expiresAt, user);
    }
}
