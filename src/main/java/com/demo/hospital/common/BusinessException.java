package com.demo.hospital.common;

/**
 * 业务异常基类。
 *
 * <p>与 {@code AuthException} 是同一种模式：<b>异常携带错误码，但不决定 HTTP 状态码</b>。
 * 映射集中在 {@code GlobalExceptionHandler}，这样同一个错误码在任何控制器里
 * 都返回同一个状态码——前端才能依赖它。
 *
 * <p>之所以现在（T-003）就建这个类，而不是等到 T-004 再建：
 * 它是 T-004~T-012 所有模块共用的出口。等第一个用它的任务去建，
 * 必然会有别人先各写一个自己的版本。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    /**
     * 请求参数不合法。
     *
     * <p>把消息拼成"参数名 + 原因"的形式，因为这条消息会直接返回给调用方，
     * 它应该能指出到底哪个参数错了。
     */
    public static BusinessException invalidParameter(String message) {
        return new BusinessException(ErrorCode.INVALID_PARAMETER, message);
    }
}
