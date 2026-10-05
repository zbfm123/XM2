package com.demo.hospital.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.appointment.domain.Appointment;
import com.demo.hospital.appointment.domain.AppointmentStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 挂号订单持久化。
 *
 * <p>⚠️ <b>所有按"我的订单"查询的语句都必须带 {@code user_id} 条件</b>。
 * 漏掉它 = 一个用户能看到别人的挂号记录。本项目用测试锁住这一点
 * （见 {@code AppointmentIsolationTest}），而不是靠人记得。
 */
@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    /**
     * 按幂等键查已有订单。
     *
     * <p>这是幂等的<b>快速路径</b>：同一个键重复提交时，直接返回第一次创建的订单，
     * 不再走一遍扣号源。唯一索引是慢路径上的最终防线。
     */
    @Select("""
            SELECT id, appointment_no, user_id, schedule_id, doctor_id, department_id,
                   idempotency_key, visit_date, period, fee, status, expire_at,
                   cancel_reason, created_at, updated_at
              FROM appointment
             WHERE idempotency_key = #{idempotencyKey}
            """)
    Appointment findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 按业务单号查。<b>必须带 user_id</b>：否则知道单号就能看别人的订单。 */
    @Select("""
            SELECT id, appointment_no, user_id, schedule_id, doctor_id, department_id,
                   idempotency_key, visit_date, period, fee, status, expire_at,
                   cancel_reason, created_at, updated_at
              FROM appointment
             WHERE appointment_no = #{appointmentNo}
               AND user_id = #{userId}
            """)
    Appointment findByNoAndUser(@Param("appointmentNo") String appointmentNo,
                                @Param("userId") Long userId);

    /**
     * 同一患者对同一排班的<b>活跃</b>订单。
     *
     * <p>⚠️ {@code status <> 'CANCELLED'} 这个条件是必需的，不是可选的过滤：
     * 它对应数据库上的 {@code uk_appointment_active_slot}（活跃订单唯一）。
     *
     * <p>少了它会发生什么：用户取消后想重新挂同一个号，
     * 应用层先查到那条<b>已取消</b>的记录，于是回一句"您已挂过该排班"——
     * 而他的号明明已经取消了。<b>这条缺陷是测试抓出来的</b>：
     * {@code canRebookAfterCancel} 报 409，因为应用层与索引对"唯一"的理解不一致。
     *
     * <p>教训：<b>应用层的判断条件和数据库的唯一约束必须表达同一个语义。</b>
     * 两者不一致时，要么误拒正常操作（本例），要么让唯一索引频繁抛异常。
     */
    @Select("""
            SELECT id, appointment_no, user_id, schedule_id, doctor_id, department_id,
                   idempotency_key, visit_date, period, fee, status, expire_at,
                   cancel_reason, created_at, updated_at
              FROM appointment
             WHERE user_id = #{userId}
               AND schedule_id = #{scheduleId}
               AND status <> 'CANCELLED'
            """)
    Appointment findActiveByUserAndSchedule(@Param("userId") Long userId,
                                            @Param("scheduleId") Long scheduleId);

    /**
     * 状态迁移（带"起始状态"条件的原子 UPDATE）。
     *
     * <p>⚠️ <b>为什么把 {@code fromStatus} 写进 WHERE 而不是先查再改</b>：
     * 与号源扣减完全同一个道理。取消订单时如果先查出"当前是 PENDING_PAYMENT"
     * 再去更新，那么在"查"和"改"之间，订单可能已经被支付了——
     * 于是<b>一笔已付款的订单被取消，而号源被归还</b>。
     *
     * <p>把"当前状态必须是 X"压进 WHERE，由数据库保证原子性：
     * 受影响行数为 0 就说明状态已经变了，调用方据此拒绝本次操作。
     *
     * @return 受影响行数：1 = 迁移成功，0 = 当前状态不是 {@code fromStatus}（已被别人改过）
     */
    @Update("""
            UPDATE appointment
               SET status = #{toStatus},
                   cancel_reason = #{cancelReason},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = #{fromStatus}
            """)
    int transitionStatus(@Param("id") Long id,
                         @Param("fromStatus") AppointmentStatus fromStatus,
                         @Param("toStatus") AppointmentStatus toStatus,
                         @Param("cancelReason") String cancelReason);

    /** 我的挂号列表（按状态筛选，分页）。<b>user_id 是必带条件。</b> */
    @Select("""
            <script>
            SELECT id, appointment_no, user_id, schedule_id, doctor_id, department_id,
                   idempotency_key, visit_date, period, fee, status, expire_at,
                   cancel_reason, created_at, updated_at
              FROM appointment
             WHERE user_id = #{userId}
            <if test="status != null">
               AND status = #{status}
            </if>
             ORDER BY id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Appointment> findPageByUser(@Param("userId") Long userId,
                                     @Param("status") AppointmentStatus status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /** 与 {@link #findPageByUser} 同一套过滤条件的总数。 */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM appointment
             WHERE user_id = #{userId}
            <if test="status != null">
               AND status = #{status}
            </if>
            </script>
            """)
    long countByUser(@Param("userId") Long userId,
                     @Param("status") AppointmentStatus status);

    /**
     * 我的挂号列表（带医生与科室的展示字段）。
     *
     * <p>⚠️ 每个列都显式起了别名，别名 = {@link AppointmentRow} 的字段名。
     * 原因见 {@code ScheduleMapper} 的注释：doctor 与 department 都有 {@code name} 列，
     * 裸写 {@code d.name, dept.name} 会让构造器映射选错列，<b>而且不报错</b>。
     * 那次是"科室名显示成了医生名"，靠测试断言具体字段值才抓出来。
     */
    @Select("""
            <script>
            SELECT a.appointment_no  AS appointmentNo,
                   a.schedule_id     AS scheduleId,
                   d.name            AS doctorName,
                   dept.name         AS departmentName,
                   a.visit_date      AS visitDate,
                   a.period          AS period,
                   a.fee             AS fee,
                   a.status          AS status,
                   a.expire_at       AS expireAt,
                   a.cancel_reason   AS cancelReason,
                   a.created_at      AS createdAt
              FROM appointment a
              JOIN doctor d        ON d.id = a.doctor_id
              JOIN department dept ON dept.id = a.department_id
             WHERE a.user_id = #{userId}
            <if test="status != null">
               AND a.status = #{status}
            </if>
             ORDER BY a.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AppointmentRow> findPageRowsByUser(@Param("userId") Long userId,
                                            @Param("status") AppointmentStatus status,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    /** 单条订单的展示信息（下单成功、取消后回显都用它）。<b>user_id 必带。</b> */
    @Select("""
            SELECT a.appointment_no  AS appointmentNo,
                   a.schedule_id     AS scheduleId,
                   d.name            AS doctorName,
                   dept.name         AS departmentName,
                   a.visit_date      AS visitDate,
                   a.period          AS period,
                   a.fee             AS fee,
                   a.status          AS status,
                   a.expire_at       AS expireAt,
                   a.cancel_reason   AS cancelReason,
                   a.created_at      AS createdAt
              FROM appointment a
              JOIN doctor d        ON d.id = a.doctor_id
              JOIN department dept ON dept.id = a.department_id
             WHERE a.appointment_no = #{appointmentNo}
               AND a.user_id = #{userId}
            """)
    AppointmentRow findRowByNoAndUser(@Param("appointmentNo") String appointmentNo,
                                      @Param("userId") Long userId);

    /**
     * 删除某用户的全部订单。
     *
     * <p>⚠️ <b>仅供测试清理使用，业务代码不得调用。</b>
     * 业务上"取消订单"是改状态（{@link #transitionStatus}），不是删行——
     * 删掉订单会让号源归还失去依据，也让历史记录凭空消失。
     */
    @Delete("DELETE FROM appointment WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
