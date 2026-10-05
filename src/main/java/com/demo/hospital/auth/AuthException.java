package com.demo.hospital.auth;

/**
 * 认证类失败。携带错误码，由 {@code GlobalExceptionHandler} 统一映射为 HTTP 响应。
 *
 * <p>异常只表达"业务上发生了什么"，<b>不决定 HTTP 状态码</b>——
 * 状态码的映射集中在一处（见 GlobalExceptionHandler），否则同一个错误码
 * 在不同控制器里可能返回不同的状态码，前端就无法依赖它。
 */
public class AuthException extends RuntimeException {

    private final AuthErrorCode code;
    private final Long unlockAtEpochSeconds;

    public AuthException(AuthErrorCode code, String message) {
        this(code, message, null);
    }

    public AuthException(AuthErrorCode code, String message, Long unlockAtEpochSeconds) {
        super(message);
        this.code = code;
        this.unlockAtEpochSeconds = unlockAtEpochSeconds;
    }

    public AuthErrorCode getCode() {
        return code;
    }

    /** 仅 {@link AuthErrorCode#ACCOUNT_LOCKED} 时有值，方便前端显示"还需等待多久"。 */
    public Long getUnlockAtEpochSeconds() {
        return unlockAtEpochSeconds;
    }
}
