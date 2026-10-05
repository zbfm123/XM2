package com.demo.hospital.auth;

import com.demo.hospital.auth.domain.IssuedToken;
import com.demo.hospital.auth.jwt.JwtService;
import com.demo.hospital.user.domain.SysUser;
import com.demo.hospital.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 注册、登录与当前用户查询。
 *
 * <p>这个类里有两处判断值得单独说，它们都是"看起来琐碎但错了会出事"的那一类：
 * <ol>
 *   <li><b>校验顺序</b>——锁定检查必须先于口令校验（见 {@link #login}）</li>
 *   <li><b>注册的并发</b>——先查重再插入是<b>有窗口</b>的，唯一索引才是最终防线（见 {@link #register}）</li>
 * </ol>
 */
@Service
public class AuthService {

    /** 注册时未填姓名时使用的默认值：中性、且一眼能看出不是真实姓名。 */
    private static final String DEFAULT_REAL_NAME = "患者";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptGuard guard;

    public AuthService(SysUserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginAttemptGuard guard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.guard = guard;
    }

    /**
     * 注册。
     *
     * <p><b>为什么"先查重"之后还要 catch 唯一索引冲突</b>：
     * 先查再插在并发下是有窗口的——两个请求可以同时查到"手机号可用"，
     * 然后一个成功、一个撞在唯一索引上抛 500。
     * 先查只是为了给出友好提示，<b>数据库唯一索引才是"数据一定正确"的保证</b>。
     * 这也正是本项目对挂号所持的态度（docs/02 的 D-04），在此处先一致起来。
     */
    @Transactional
    public SysUser register(String phone, String rawPassword, String realName) {
        if (userMapper.findByPhone(phone) != null) {
            throw new AuthException(AuthErrorCode.PHONE_ALREADY_REGISTERED, "该手机号已注册，请直接登录");
        }

        SysUser user = new SysUser();
        user.setPhone(phone);
        // ⚠️ 只存哈希。BCrypt 自带盐，因此相同的口令在两个账号上得到的哈希不同——
        //    这让"撞库比对哈希"失去意义。
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRealName(realName == null || realName.isBlank() ? DEFAULT_REAL_NAME : realName.trim());
        user.setIdCard(null);
        user.setEnabled(true);
        user.setFailedCount(0);
        user.setLockedUntil(null);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 走到这里说明上面的查重被并发绕过了。对调用方而言结果一样：手机号已被占用。
            throw new AuthException(AuthErrorCode.PHONE_ALREADY_REGISTERED, "该手机号已注册，请直接登录");
        }
        return user;
    }

    /**
     * 登录。
     *
     * <p>校验顺序本身是设计的一部分：
     * <ol>
     *   <li><b>锁定检查先于口令校验</b>——锁定期内连口令都不该验，
     *       否则"锁定"只是一个好看的提示，攻击者照样能继续爆破。</li>
     *   <li>启用检查。</li>
     *   <li>口令校验。</li>
     * </ol>
     *
     * <p>口令错误时统一返回 {@code BAD_CREDENTIALS}，<b>不区分"账号不存在"与"口令错误"</b>：
     * 否则这个接口就成了一个"这个手机号注册过没有"的查询工具。
     *
     * <p>⚠️ <b>{@code noRollbackFor = AuthException.class} 不是可以省的注解，去掉它锁定就失效了。</b>
     * 这里踩过一次真实的坑（记在 docs/PROGRESS.md）：
     * 失败计数写在 {@code sys_user.failed_count}，而"口令错误"恰恰要抛异常。
     * Spring 默认只在遇到 {@code RuntimeException} 时回滚——于是
     * <b>计数更新和异常一起被回滚了，数据库里永远是 0，账号永远不会被锁定</b>。
     * 表现是"锁定功能看起来做完了、单测也全绿，但线上试一百次也不会锁"。
     *
     * <p>允许提交的理由：{@code AuthException} 表达的是<b>预期的业务结果</b>
     * （口令不对、账号锁定），不是"这次操作没有发生"。失败计数本身就是这次操作
     * 唯一的持久化结果——把它回滚掉等于什么都没做。
     * 真正的异常（数据库故障、空指针）仍然是 {@code RuntimeException} 的其它子类，照常回滚。
     */
    @Transactional(noRollbackFor = AuthException.class)
    public LoginResult login(String phone, String rawPassword) {
        SysUser user = userMapper.findByPhone(phone);

        if (user == null) {
            // 刻意不做差异化提示，也不累加计数（没有用户行可累加）。
            // 防枚举靠统一错误码，防爆破靠真实账号的锁定。
            throw new AuthException(AuthErrorCode.BAD_CREDENTIALS, "手机号或口令错误");
        }

        LocalDateTime lockUntil = guard.lockedUntil(user);
        if (lockUntil != null) {
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED,
                    "账号已锁定，请稍后再试", toEpochSeconds(lockUntil));
        }

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException(AuthErrorCode.ACCOUNT_DISABLED, "账号已停用");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            LocalDateTime triggeredLock = guard.recordFailure(user);
            if (triggeredLock != null) {
                // 本次失败刚好触发锁定：告诉用户"被锁了"比告诉他"口令错了"更有用，
                // 因为前者决定了他接下来该做什么（等待），后者只会让他继续试。
                throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED,
                        "连续失败次数过多，账号已锁定", toEpochSeconds(triggeredLock));
            }
            throw new AuthException(AuthErrorCode.BAD_CREDENTIALS, "手机号或口令错误");
        }

        guard.recordSuccess(user);

        IssuedToken issued = jwtService.issue(user.getId());
        return new LoginResult(issued, user);
    }

    /**
     * 查询当前登录用户。
     *
     * <p>这里是<b>每次请求都回库</b>，而不是信任令牌里的信息。
     * 代价是一次主键查询，换来的是：
     * <ul>
     *   <li>账号被停用后，老令牌<b>立刻</b>失效（否则要等令牌自然过期）</li>
     *   <li>用户信息改了，前端立刻看到新值</li>
     * </ul>
     * <p>这也是本项目"把手机号之类会变的字段留在库里、不塞进令牌"的理由。
     */
    public SysUser currentUser(Long userId) {
        SysUser user = userMapper.findById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException(AuthErrorCode.TOKEN_INVALID, "用户不存在或已停用");
        }
        return user;
    }

    private Long toEpochSeconds(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 登录结果。
     *
     * @param token 已签发的令牌
     * @param user  用户实体，<b>仅限服务端内部使用</b>——含口令哈希，不得直接序列化返回
     */
    public record LoginResult(IssuedToken token, SysUser user) {
    }
}
