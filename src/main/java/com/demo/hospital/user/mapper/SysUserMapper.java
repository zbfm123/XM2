package com.demo.hospital.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.user.domain.SysUser;
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

    /** 登录失败次数 +1，并可选地设置锁定时间。 */
    @Update("""
            UPDATE sys_user
               SET failed_count = failed_count + 1,
                   locked_until = #{lockedUntil}
             WHERE id = #{id}
            """)
    int increaseFailedCount(@Param("id") Long id,
                            @Param("lockedUntil") java.time.LocalDateTime lockedUntil);

    /** 登录成功：清零失败次数与锁定。 */
    @Update("""
            UPDATE sys_user
               SET failed_count = 0,
                   locked_until = NULL
             WHERE id = #{id}
            """)
    int resetFailedCount(@Param("id") Long id);
}
