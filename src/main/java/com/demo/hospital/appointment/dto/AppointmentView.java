package com.demo.hospital.appointment.dto;

import com.demo.hospital.appointment.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 挂号订单对外视图。
 *
 * <p>⚠️ <b>刻意不暴露 {@code userId}</b>：接口本身就是"我的挂号"，
 * 调用方必然知道自己的 id；返回它只会让"越权看到别人的单"这件事更容易被忽略
 * （因为响应体里就带着一个不属于你的 userId）。
 *
 * <p>也不暴露 {@code idempotencyKey}：那是客户端的重试凭据，
 * 回显它没有用处，反而会让人误以为要靠响应里的键去重试。
 *
 * @param appointmentNo 业务单号（对外唯一标识，不暴露自增 id）
 * @param scheduleId    排班 id
 * @param doctorName    医生姓名（JOIN 得到，便于前端直接展示）
 * @param departmentName 科室名（JOIN 得到）
 * @param visitDate     就诊日期
 * @param period        时段：AM / PM
 * @param fee           挂号费
 * @param status        当前状态
 * @param expireAt      待支付过期时间（T-012 会自动取消）
 * @param cancelReason  取消原因（未取消时为 null）
 * @param createdAt     下单时间
 * @param replayed      <b>本次响应是否是幂等重放</b>（即"这个订单早就存在，我没新建"）
 */
public record AppointmentView(
        String appointmentNo,
        Long scheduleId,
        String doctorName,
        String departmentName,
        LocalDate visitDate,
        String period,
        BigDecimal fee,
        AppointmentStatus status,
        LocalDateTime expireAt,
        String cancelReason,
        LocalDateTime createdAt,
        boolean replayed
) {
}
