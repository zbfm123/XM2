package com.demo.hospital.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.schedule.domain.Schedule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
