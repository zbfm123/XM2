package com.demo.hospital.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.user.domain.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户持久化。
 *
 * <p>继承了 MyBatis-Plus 的 {@link BaseMapper}，但<b>关键查询仍然手写 SQL</b>：
 * 手写才能看清有没有带对条件（例如"只查启用的账号"）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT id, phone, password_hash, real_name, id_card, enabled,
                   failed_count, locked_until, created_at, updated_at
              FROM sys_user
             WHERE phone = #{phone}
            """)
    SysUser findByPhone(@Param("phone") String phone);

    @Select("""
            SELECT id, phone, password_hash, real_name, id_card, enabled,
                   failed_count, locked_until, created_at, updated_at
              FROM sys_user
             WHERE id = #{id}
            """)
    SysUser findById(@Param("id") Long id);

    /**
     * 记录一次登录失败：计数 +1，并在需要时写入解锁时刻。
     *
     * <p>刻意把"加一"和"置锁定"放在<b>同一条 SQL</b> 里，而不是先读出来再加再写回：
     * 在同一账号上并发试密码时，读-改-写会丢计数——而丢计数恰恰帮了攻击者。
     *
     * @param failedCount 本次失败后的计数（由服务层决定是否已达阈值）
     * @param lockedUntil 达到阈值时是解锁时刻，否则为 null
     */
    @Update("""
            UPDATE sys_user
               SET failed_count = #{failedCount},
                   locked_until = #{lockedUntil}
             WHERE id = #{id}
            """)
    int recordLoginFailure(@Param("id") Long id,
                           @Param("failedCount") int failedCount,
                           @Param("lockedUntil") java.time.LocalDateTime lockedUntil);

    /** 登录成功：清零失败次数与锁定。 */
    @Update("""
            UPDATE sys_user
               SET failed_count = 0,
                   locked_until = NULL
             WHERE id = #{id}
            """)
    int resetFailedCount(@Param("id") Long id);

    /**
     * 按手机号删除。
     *
     * <p>存在的唯一理由是<b>测试的清理</b>：认证集成测试刻意不加 {@code @Transactional}
     * （原因见该测试的注释——加了会把"回滚导致锁定失效"这种 bug 藏起来），
     * 因此必须自己把造出来的数据删掉，否则会污染本机开发库。
     */
    @Delete("DELETE FROM sys_user WHERE phone = #{phone}")
    int deleteByPhone(@Param("phone") String phone);
}
