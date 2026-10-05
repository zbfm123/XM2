package com.demo.hospital.auth;

/**
 * 认证相关错误码。
 *
 * <p>为什么不用一个笼统的"登录失败"：<b>错误码的价值在于让调用方知道接下来该干什么。</b>
 * 让前端凭 message 字符串去猜"该跳登录页还是该显示倒计时"是脆弱的——
 * 文案一改，逻辑就断。
 */
public enum AuthErrorCode {

    /** 手机号或口令错误。<b>刻意不区分"账号不存在"与"口令错误"</b>，避免账号枚举。 */
    BAD_CREDENTIALS,
    /** 账号被锁定，需等待 {@code unlockAt}。 */
    ACCOUNT_LOCKED,
    /** 账号已停用。 */
    ACCOUNT_DISABLED,
    /** 注册时手机号已被占用。 */
    PHONE_ALREADY_REGISTERED,
    /** 令牌已过期。 */
    TOKEN_EXPIRED,
    /** 令牌无效（签名不符/结构损坏/issuer 不匹配）。 */
    TOKEN_INVALID,
    /** 请求需要登录但没有携带令牌。 */
    UNAUTHENTICATED,
    /** 缺少登录上下文——这是实现缺陷，不是用户问题。 */
    AUTH_CONTEXT_MISSING
}
