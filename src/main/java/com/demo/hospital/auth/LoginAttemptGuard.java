package com.demo.hospital.auth;

import com.demo.hospital.config.JwtProperties;
import com.demo.hospital.user.domain.SysUser;
import com.demo.hospital.user.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 登录失败计数与账号锁定。
 *
 * <p>为什么必须有这个东西：<b>一个没有任何限速的登录接口等于把口令交给暴力破解。</b>
 * 阈值与锁定时长来自配置（{@code app.jwt.max-failures} / {@code lock-minutes}），
 * 这样测试可以把阈值改小来验证锁定行为，而不用真的失败 5 次。
 *
 * <p>计数持久化在 {@code sys_user.failed_count} 而<b>不是只放 Redis</b>：
 * 锁定状态必须能扛住 Redis 重启——否则"清一次缓存"就等于帮攻击者重置了机会次数。
 * 这一点是从项目 1 直接沿用下来的判断。
 *
 * <p>本实现的已知边界（面试主动交代，不要等对方问）：
 * 这里限的是<b>单个账号</b>，不是单个 IP。攻击者拿一份常见口令表去撞
 * <em>很多个</em>账号时，每个账号只失败一两次，不会被锁定。
 * 真正的做法是再加一层按 IP / 按设备的滑动窗口（需要 Redis），本期不做。
 */
@Component
public class LoginAttemptGuard {

    private final SysUserMapper userMapper;
    private final JwtProperties properties;

    public LoginAttemptGuard(SysUserMapper userMapper, JwtProperties properties) {
        this.userMapper = userMapper;
        this.properties = properties;
    }

    /**
     * 账号当前是否处于锁定状态。
     *
     * @return 仍在锁定期则返回解锁时刻；否则返回 null
     */
    public LocalDateTime lockedUntil(SysUser user) {
        LocalDateTime until = user.getLockedUntil();
        if (until == null) {
            return null;
        }
        return until.isAfter(LocalDateTime.now()) ? until : null;
    }

    /**
     * 记录一次登录失败。
     *
     * <p>计数<b>不清零</b>，只在登录成功时清零：这样"每失败 4 次就成功登录一次"
     * 的绕过手法无效——连续失败的含义就是"中间没有成功过"。
     *
     * @return 触发锁定时返回解锁时刻；未触发返回 null
     */
    public LocalDateTime recordFailure(SysUser user) {
        int current = user.getFailedCount() == null ? 0 : user.getFailedCount();
        int next = current + 1;

        LocalDateTime lockUntil = null;
        if (next >= properties.maxFailures()) {
            lockUntil = LocalDateTime.now().plusMinutes(properties.lockMinutes());
        }
        userMapper.recordLoginFailure(user.getId(), next, lockUntil);
        return lockUntil;
    }

    /** 登录成功：清零失败计数与锁定。 */
    public void recordSuccess(SysUser user) {
        int failed = user.getFailedCount() == null ? 0 : user.getFailedCount();
        if (failed > 0 || user.getLockedUntil() != null) {
            userMapper.resetFailedCount(user.getId());
        }
    }
}
