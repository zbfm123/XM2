package com.demo.hospital.appointment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 挂号订单。
 *
 * <p>状态迁移由 {@link AppointmentStatus} 定义，本类只负责承载数据。
 */
@TableName("appointment")
public class Appointment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务单号，对外展示。**不暴露自增 id**（否则订单量会被外部数出来）。 */
    private String appointmentNo;

    private Long userId;
    private Long scheduleId;
    private Long doctorId;
    private Long departmentId;

    /** 幂等键，客户端提供。唯一索引 {@code uk_appointment_idem} 保证防重复提交。 */
    private String idempotencyKey;

    private LocalDate visitDate;
    private String period;
    private BigDecimal fee;
    private AppointmentStatus status;

    /** 待支付过期时间，配合 T-012 的延迟队列。 */
    private LocalDateTime expireAt;

    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppointmentNo() { return appointmentNo; }
    public void setAppointmentNo(String appointmentNo) { this.appointmentNo = appointmentNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long scheduleId) { this.scheduleId = scheduleId; }
    public Long getDoctorId() { return doctorId; }
    public void setDoctorId(Long doctorId) { this.doctorId = doctorId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDate getVisitDate() { return visitDate; }
    public void setVisitDate(LocalDate visitDate) { this.visitDate = visitDate; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public AppointmentStatus getStatus() { return status; }
    public void setStatus(AppointmentStatus status) { this.status = status; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
