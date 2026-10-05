package com.demo.hospital.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.schedule.domain.Schedule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班（号源）持久化。
 *
 * <p>⚠️ <b>本接口目前只有只读查询。</b>扣减号源的 {@code tryDeduct} 属于 T-006，
 * 那时会在这里加一条原子 UPDATE。现在不提前加，是有意的：
 * "先随手写一个可能写错的扣减"正是防超卖最危险的做法，
 * 而让它出现在 T-006 的 diff 里，评审时一眼就能看到"这条 SQL 到底长什么样"。
 *
 * <p>分页约定（与 {@code PageResult} 一致）：
 * <b>Mapper 只接受已经算好的 {@code limit} / {@code offset}（0 基）</b>，
 * 1 基 → 0 基的转换只在 Service 层发生一次。项目 1 在混用两种基数的页码上踩过坑。
 */
@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /**
     * 按医生查排班（含医生与科室的展示字段），可分页、可按日期区间过滤。
     *
     * <p>⚠️ <b>每个列都显式起了别名，这不是啰嗦，是必需的。</b>
     * 医生表和科室表都有 {@code name} 列，JOIN 之后结果集里出现两个同名标签，
     * 靠位置的构造器映射会在它们之间选错——<b>而且不报错</b>，
     * 表现是"科室名那一栏显示的是医生名"这种看起来毫无道理的现象。
     * （这不是假设：本查询最初就写成了 {@code d.name, dept.name}，
     * 测试立刻报 {@code departmentName expected:<集成测试科室> but was:<测试医生>}。）
     *
     * <p>别名直接用 {@link ScheduleRow} 的字段名，让映射变成<b>按名字</b>而不是按位置：
     * 这样即使将来调整 SELECT 的顺序，也不会静默地串字段。
     *
     * @param doctorId 医生 id
     * @param from     起始日期（含），可为 null
     * @param to       结束日期（含），可为 null
     * @param limit    每页条数
     * @param offset   跳过的条数（0 基）
     */
    @Select("""
            <script>
            SELECT s.id              AS id,
                   s.doctor_id       AS doctorId,
                   d.name            AS doctorName,
                   d.title           AS doctorTitle,
                   s.department_id   AS departmentId,
                   dept.name         AS departmentName,
                   s.work_date       AS workDate,
                   s.period          AS period,
                   s.total_slots     AS totalSlots,
                   s.remaining_slots AS remainingSlots,
                   s.fee             AS fee
              FROM schedule s
              JOIN doctor d        ON d.id = s.doctor_id
              JOIN department dept ON dept.id = s.department_id
             WHERE s.doctor_id = #{doctorId}
            <if test="from != null">
               AND s.work_date &gt;= #{from}
            </if>
            <if test="to != null">
               AND s.work_date &lt;= #{to}
            </if>
             ORDER BY s.work_date, s.period, s.id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ScheduleRow> findPageByDoctor(@Param("doctorId") Long doctorId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /** 与 {@link #findPageByDoctor} 完全同一套过滤条件的总条数（否则分页会错位）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM schedule s
             WHERE s.doctor_id = #{doctorId}
            <if test="from != null">
               AND s.work_date &gt;= #{from}
            </if>
            <if test="to != null">
               AND s.work_date &lt;= #{to}
            </if>
            </script>
            """)
    long countByDoctor(@Param("doctorId") Long doctorId,
                       @Param("from") LocalDate from,
                       @Param("to") LocalDate to);

    /**
     * 删除某医生的全部排班。
     *
     * <p>⚠️ <b>仅供测试清理使用，业务代码不得调用。</b>
     * 写在 Mapper 上而不是让测试自己拼 SQL，是为了让清理语句只有一处定义；
     * 用一个明确的 {@code @Delete} 而不是 MyBatis-Plus 的通用条件构造器，
     * 也是为了让"它到底删了什么"一眼可见——清理代码最怕的就是删多了。
     */
    @Delete("DELETE FROM schedule WHERE doctor_id = #{doctorId}")
    int deleteByDoctorId(@Param("doctorId") Long doctorId);

    // ==================================================================
    // T-006 号源扣减 —— 本项目技术内核
    // ==================================================================

    /**
     * 尝试扣减一个号源。<b>这是全项目最重要的一条 SQL。</b>
     *
     * <h2>为什么必须是这一个语句</h2>
     *
     * 错误写法（"先查再改"）：
     * <pre>
     * var s = mapper.selectById(id);       // 线程 A、B 都读到 remaining = 1
     * if (s.getRemainingSlots() &gt; 0) {     // A、B 都通过这道判断
     *     mapper.decrement(id);            // 都减 → remaining = -1，超卖
     * }
     * </pre>
     * <b>判断与扣减之间的那个窗口，就是超卖的唯一来源。</b>
     * 加 {@code synchronized} 只在单机有效（多实例直接失效），而且把并发变成了串行——
     * 那是用锁掩盖问题，不是解决问题。
     *
     * <p>正确写法就是把判断和扣减压成<b>一条语句</b>：
     * {@code WHERE remaining_slots > 0} 与 {@code SET remaining_slots = remaining_slots - 1}
     * 在同一个语句里，由数据库的行锁保证原子性。中间没有窗口，所以不可能超卖。
     *
     * <h2>为什么用"受影响行数"回答问题</h2>
     *
     * 返回值 {@code 1} = 抢到了，{@code 0} = 没抢到（号源已满）。
     * <b>不需要再查一次库</b>，也不需要靠异常来判断——
     * "我到底抢到没有"就是数据库告诉我们的这个数字。
     *
     * <h2>为什么失败时不做任何补偿</h2>
     *
     * 没扣到就是没扣到，不存在中间状态。这里没有"回滚"可言，
     * 调用方拿 {@code 0} 直接抛业务异常即可。
     *
     * <h2>⚠️ 为什么不用 {@code UPDATE ... SET remaining = remaining - 1} 不带条件</h2>
     *
     * 不带 {@code remaining_slots > 0} 的话，号源会被减成负数。
     * 负数余额在业务上毫无意义，而且它<b>会静默地掩盖超卖</b>：
     * 数据库里不再是"刚好 0"，而是一个负数，事后很难判断到底多卖了多少个。
     *
     * @param scheduleId 排班 id
     * @return 受影响行数：<b>1 = 扣减成功，0 = 号源已满（或排班不存在）</b>
     */
    @Update("""
            UPDATE schedule
               SET remaining_slots = remaining_slots - 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{scheduleId}
               AND remaining_slots > 0
            """)
    int tryDeduct(@Param("scheduleId") Long scheduleId);

    /**
     * 归还一个号源（取消挂号时调用）。<b>带上界判断，这一点极易被忽略。</b>
     *
     * <h2>为什么必须有 {@code remaining_slots < total_slots}</h2>
     *
     * 归还如果不判断上界，<b>重复取消就会把号源加到超过总数</b>：
     * <pre>
     * 总号源 20，已被扣到 15
     * 取消一次 → 16 ✓
     * 同一次取消被重试 → 17 ✗ 号源凭空变多了
     * ...最终 → 21，比总号源还多
     * </pre>
     * 号源变多比变少更危险：它意味着<b>数据库里的数字已经不再可信</b>，
     * 而"不超卖"这个保证是建立在"remaining_slots 恰好等于剩余量"之上的。
     *
     * <p>所以归还语句必须是：
     * {@code SET remaining = remaining + 1 WHERE remaining < total_slots}。
     * 幂等性由 {@code (user_id, schedule_id)} 唯一索引加上这个上界共同保证。
     *
     * <p>返回值同样是受影响行数：{@code 0} 表示"已经到了总数上限，这次归还没生效"，
     * 调用方据此可以判断出"有人在重复取消"。
     *
     * @param scheduleId 排班 id
     * @return 受影响行数：1 = 归还成功，0 = 已满（重复取消或数据异常）
     */
    @Update("""
            UPDATE schedule
               SET remaining_slots = remaining_slots + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{scheduleId}
               AND remaining_slots < total_slots
            """)
    int tryReturn(@Param("scheduleId") Long scheduleId);
}
