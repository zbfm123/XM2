package com.demo.hospital.appointment.mapper;

import com.demo.hospital.appointment.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 挂号订单的<b>读模型</b>：一行 = "前端要看到的一条挂号记录"。
 *
 * <p>比实体多出 {@code doctorName} 与 {@code departmentName} 两个 JOIN 出来的展示字段。
 * 列表页要显示"张伟民 · 内科 · 2026-10-06 上午"，如果只给 id，
 * 前端就得为每一行再查一次医生和科室（N+1 次请求）。
 *
 * <p>⚠️ 与 {@code ScheduleRow} 同样的纪律：{@code ScheduleMapper} 那次的教训是
 * <b>两个表都有同名列时必须显式起别名</b>，否则构造器映射会静默串位。
 * 本查询同样 JOIN 了 doctor 与 department，所以别名一个都不能省。
 */
public record AppointmentRow(
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
        LocalDateTime createdAt
) {
}
