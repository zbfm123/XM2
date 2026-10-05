package com.demo.hospital.auth.domain;

/**
 * 请求级"当前登录用户"上下文。
 *
 * <p>存在理由：业务代码（挂号、我的挂号列表）需要知道"这是谁"。
 * 如果把这个 {@code userId} 作为参数层层透传，签名会被与业务无关的参数污染，
 * 而且<b>漏传是一定会发生的</b>——漏传的后果是把 A 的挂号写成 B 的。
 *
 * <p>⚠️ 两条纪律（与项目 1 相同，都是踩过坑才写下的）：
 * <ol>
 *   <li>请求结束<b>必须</b> {@link #clear()}：Tomcat 线程池会复用线程，
 *       不清理会让下一个请求读到上一个用户的 id——最严重的一类串号 bug。</li>
 *   <li>取不到上下文时调用方应当<b>失败</b>，绝不退化成"查全部数据"。
 *       见 {@link #require()}。</li>
 * </ol>
 *
 * <p>本项目只有一个角色（患者），因此没有 {@code role} 字段——
 * 不预留用不上的抽象（见 docs/01 的"不做清单"：不做 RBAC）。
 */
public final class CurrentUser {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private final Long userId;

    private CurrentUser(Long userId) {
        this.userId = userId;
    }

    public static void set(Long userId) {
        HOLDER.set(new CurrentUser(userId));
    }

    /** 取当前用户；未登录返回 null，由调用方自行决定如何处理。 */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户，未登录直接抛异常。
     *
     * <p>需要用户隔离的场景必须用这个方法：<b>拿不到就失败，绝不返回 null 让上层去猜。</b>
     * 返回 null 的写法迟早会演变成"查不到用户就查全部"——那是数据泄露。
     */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) {
            throw new IllegalStateException("缺少登录上下文：该操作必须在已认证的请求内执行");
        }
        return u;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String toString() {
        return "CurrentUser{userId=" + userId + '}';
    }
}
