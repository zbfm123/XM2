package com.demo.hospital.auth.domain;

import java.time.Instant;

/**
 * 登录成功后返回的令牌与其元信息。
 *
 * @param token     JWT 字符串
 * @param expiresAt 过期时刻（UTC）
 * @param expiresIn 有效秒数，前端可据此安排"何时该重新登录"
 */
public record IssuedToken(String token, Instant expiresAt, long expiresIn) {
}
