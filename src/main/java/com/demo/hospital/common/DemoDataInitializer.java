package com.demo.hospital.common;

import com.demo.hospital.user.domain.SysUser;
import com.demo.hospital.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 演示账号初始化（仅 dev 环境）。
 *
 * <p><b>为什么不把 BCrypt 哈希直接写进 {@code data.sql}</b>：
 *
 * <p>项目 1 是那么做的，代价是哈希成了一个<em>无法验证的魔法字符串</em>——
 * 你必须另外记住它对应什么口令，改了编码强度还得重新生成并手工替换。
 * 一旦它对不上，演示时就是"登录不上"，而错误现场只会告诉你"口令错误"。
 *
 * <p>这里改成启动时用 {@link PasswordEncoder} 现算：
 * <ul>
 *   <li><b>口令明文写在代码里，哈希由程序生成</b>——两者天然一致，不可能对不上</li>
 *   <li>明文口令本来就是公开的演示数据（{@code Demo@2026}），公开它不构成风险</li>
 *   <li>换编码强度或换实现时，无需手工重新生成</li>
 * </ul>
 *
 * <p>⚠️ 只在 {@code dev} 环境注册。生产环境绝不能有硬编码口令的账号。
 *
 * <p>⚠️ 口令公开这件事，前提是**这个账号只存在于本地演示库**。
 * 若将来真部署到公网，这里必须先删掉。
 */
@Configuration
@Profile("dev")
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** 演示口令。刻意公开：本地演示数据，且写在代码里可避免哈希与口令对不上。 */
    public static final String DEMO_PASSWORD = "Demo@2026";

    @Bean
    public ApplicationRunner initDemoUser(SysUserMapper userMapper, PasswordEncoder encoder) {
        return args -> {
            String phone = "13800000001";
            if (userMapper.findByPhone(phone) != null) {
                log.info("演示账号已存在，跳过（幂等）: {}", phone);
                return;
            }

            SysUser user = new SysUser();
            user.setPhone(phone);
            user.setPasswordHash(encoder.encode(DEMO_PASSWORD));
            user.setRealName("演示患者");
            user.setEnabled(true);
            user.setFailedCount(0);
            userMapper.insert(user);

            log.info("演示账号已创建: 手机号={} 口令={}（仅本地演示用）", phone, DEMO_PASSWORD);
        };
    }
}
