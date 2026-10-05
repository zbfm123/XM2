package com.demo.hospital.user.mapper;

import com.demo.hospital.user.domain.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户持久化与<b>唯一索引</b>的测试。
 *
 * <p>为什么专门测"数据库会拒绝重复手机号"：这是本项目第二道防线的一个具体实例
 * （与 T-007 的挂号唯一索引同一种思路）。
 *
 * <p>应用层的"先查重"是有窗口的——它能给用户友好提示，但<b>不能保证数据正确</b>。
 * 保证数据正确的是唯一索引。所以"索引真的建出来了、真的会拦"这件事必须有测试，
 * 否则某次改 schema 时删掉了索引，所有测试依然全绿。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SysUserMapperTest {

    private static final String TEST_SECRET = "unit-test-placeholder-only";

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("按手机号查询能取回全部字段（含锁定状态）")
    void findByPhoneShouldReturnAllFields() {
        String phone = uniquePhone();
        SysUser user = newUser(phone);
        userMapper.insert(user);

        SysUser loaded = userMapper.findByPhone(phone);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getPhone()).isEqualTo(phone);
        assertThat(loaded.getPasswordHash()).startsWith("$2");
        assertThat(loaded.getEnabled()).isTrue();
        assertThat(loaded.getFailedCount()).isZero();
        assertThat(loaded.getLockedUntil()).isNull();
        assertThat(loaded.isLocked()).isFalse();
    }

    @Test
    @DisplayName("findById 与 findByPhone 取到的是同一个用户（登录后 /me 依赖它）")
    void findByIdShouldMatchFindByPhone() {
        String phone = uniquePhone();
        userMapper.insert(newUser(phone));

        SysUser byPhone = userMapper.findByPhone(phone);
        SysUser byId = userMapper.findById(byPhone.getId());

        assertThat(byId).isNotNull();
        assertThat(byId.getPhone()).isEqualTo(phone);
    }

    @Test
    @DisplayName("唯一索引真的会拒绝重复手机号——这是并发注册的最终防线")
    void duplicatePhoneShouldBeRejectedByUniqueIndex() {
        String phone = uniquePhone();
        userMapper.insert(newUser(phone));

        assertThatThrownBy(() -> userMapper.insert(newUser(phone)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("记录失败次数与解锁时刻能被读回，清零后回到初始状态")
    void failureCounterRoundTrip() {
        String phone = uniquePhone();
        userMapper.insert(newUser(phone));
        SysUser user = userMapper.findByPhone(phone);

        java.time.LocalDateTime until = java.time.LocalDateTime.now().plusMinutes(15);
        userMapper.recordLoginFailure(user.getId(), 5, until);

        SysUser locked = userMapper.findByPhone(phone);
        assertThat(locked.getFailedCount()).isEqualTo(5);
        assertThat(locked.getLockedUntil()).isNotNull();
        assertThat(locked.isLocked()).isTrue();

        userMapper.resetFailedCount(user.getId());

        SysUser reset = userMapper.findByPhone(phone);
        assertThat(reset.getFailedCount()).isZero();
        assertThat(reset.getLockedUntil()).isNull();
        assertThat(reset.isLocked()).isFalse();
    }

    /**
     * 构造一个新用户。
     *
     * <p>显式设置 {@code failedCount=0} 而不是依赖数据库默认值：
     * MyBatis-Plus 的 insert 会把字段全部写进 SQL，若为 null 则写入 NULL，
     * 而 {@code failed_count} 是 NOT NULL 列——那会直接报错。
     * <b>与其依赖"默认值恰好被跳过"，不如在代码里说清楚初值是什么。</b>
     */
    private SysUser newUser(String phone) {
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(TEST_SECRET));
        user.setRealName("测试患者");
        user.setEnabled(true);
        user.setFailedCount(0);
        return user;
    }

    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "138" + String.format("%08d", n);
    }
}
