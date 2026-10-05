package com.demo.hospital.auth;

import com.demo.hospital.auth.jwt.JwtService;
import com.demo.hospital.config.JwtProperties;
import com.demo.hospital.user.domain.SysUser;
import com.demo.hospital.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册与登录逻辑的单元测试。
 *
 * <p>这里只测<b>集成测试测不到、或测起来不可靠的那一段</b>：
 * <ul>
 *   <li>并发注册时"先查重"被绕过的那条路径</li>
 *   <li>失败计数恰好达到阈值时的分支</li>
 * </ul>
 *
 * <p>为什么并发那条必须用单元测试：它要求两个请求在同一瞬间注册同一个手机号，
 * 集成测试里即使起两个线程也可能因时序错过窗口——那测试就变成了随机绿，
 * 比没有测试更危险。直接注入 {@code DuplicateKeyException}，
 * 测的是那段真正的处理代码，而不是碰运气复现并发。
 */
class AuthServiceTest {

    private static final String TEST_SECRET = "unit-test-placeholder-only";
    private static final String PHONE = "13900000001";

    private SysUserMapper userMapper;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);

        JwtProperties properties = new JwtProperties(
                "unit-test-secret-must-be-at-least-32-bytes-long",
                "hospital-appointment",
                120, 5, 15);

        authService = new AuthService(
                userMapper,
                new BCryptPasswordEncoder(),
                new JwtService(properties),
                new LoginAttemptGuard(userMapper, properties));
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    @Test
    @DisplayName("先查重命中时直接拒绝，不尝试插入")
    void duplicateFoundByPreCheckShouldBeRejectedWithoutInsert() {
        when(userMapper.findByPhone(PHONE)).thenReturn(new SysUser());

        assertThatThrownBy(() -> authService.register(PHONE, TEST_SECRET, "测试患者"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getCode())
                .isEqualTo(AuthErrorCode.PHONE_ALREADY_REGISTERED);

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("唯一索引拦截并发注册时，返回同一个业务错误码而不是 500")
    void duplicateKeyFromUniqueIndexShouldBeMappedToBusinessError() {
        // 模拟：查重时对方还没插进去（返回 null），插入时撞上唯一索引
        when(userMapper.findByPhone(PHONE)).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_user_phone"));

        assertThatThrownBy(() -> authService.register(PHONE, TEST_SECRET, "测试患者"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getCode())
                .isEqualTo(AuthErrorCode.PHONE_ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("注册写入的是哈希，姓名留空时给中性默认值")
    void registerShouldPersistHashAndDefaultName() {
        when(userMapper.findByPhone(PHONE)).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenReturn(1);

        authService.register(PHONE, TEST_SECRET, "  ");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        SysUser inserted = captor.getValue();

        assertThat(inserted.getPasswordHash()).startsWith("$2").isNotEqualTo(TEST_SECRET);
        assertThat(inserted.getRealName()).isEqualTo("患者");
        assertThat(inserted.getEnabled()).isTrue();
        assertThat(inserted.getFailedCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 登录失败与锁定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("口令错误累计到阈值时返回 ACCOUNT_LOCKED 并写入解锁时刻")
    void failuresUpToThresholdShouldLockAccount() {
        SysUser user = enabledUser();
        user.setFailedCount(4);                        // 已经有 4 次失败
        when(userMapper.findByPhone(PHONE)).thenReturn(user);

        assertThatThrownBy(() -> authService.login(PHONE, "wrong-secret"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED);

        verify(userMapper).recordLoginFailure(eq(user.getId()), eq(5), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("未到阈值时只累加计数，不写锁定时间")
    void failuresBelowThresholdShouldNotLock() {
        SysUser user = enabledUser();
        user.setFailedCount(1);
        when(userMapper.findByPhone(PHONE)).thenReturn(user);

        assertThatThrownBy(() -> authService.login(PHONE, "wrong-secret"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getCode())
                .isEqualTo(AuthErrorCode.BAD_CREDENTIALS);

        // lockedUntil 必须是 null：写上一个过去的时刻会让"是否锁定"的判断变得含糊
        verify(userMapper).recordLoginFailure(eq(user.getId()), eq(2), eq(null));
    }

    @Test
    @DisplayName("锁定期内即使口令正确也被拒绝")
    void lockedAccountShouldBeRejectedBeforePasswordCheck() {
        SysUser user = enabledUser();
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userMapper.findByPhone(PHONE)).thenReturn(user);

        assertThatThrownBy(() -> authService.login(PHONE, TEST_SECRET))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("锁定时间已过则视为未锁定，可以正常登录")
    void expiredLockShouldNotBlockLogin() {
        SysUser user = enabledUser();
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));   // 已经过期
        when(userMapper.findByPhone(PHONE)).thenReturn(user);

        AuthService.LoginResult result = authService.login(PHONE, TEST_SECRET);

        assertThat(result.token().token()).isNotBlank();
        assertThat(result.user().getId()).isEqualTo(user.getId());
    }

    private SysUser enabledUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setPhone(PHONE);
        user.setPasswordHash(new BCryptPasswordEncoder().encode(TEST_SECRET));
        user.setRealName("测试患者");
        user.setEnabled(true);
        user.setFailedCount(0);
        return user;
    }
}
