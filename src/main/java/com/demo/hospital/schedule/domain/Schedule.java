package com.demo.hospital.schedule.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 排班（号源）。
 *
 * <p><b>本项目的技术内核所在</b>：{@code remainingSlots} 是并发扣减的对象。
 *
 * <p>⚠️ 关于 {@code remainingSlots} 的一条纪律（T-006 会执行）：
 * 它<b>绝不允许"先读出来、在 Java 里减一、再写回去"</b>。
 * 那种写法在两个线程之间有一个窗口，一定会超卖。
 * 唯一正确的做法是一条带条件的原子 SQL：
 * <pre>
 * UPDATE schedule SET remaining_slots = remaining_slots - 1
 *  WHERE id = ? AND remaining_slots &gt; 0
 * </pre>
 * 用受影响行数判断"我到底抢到没有"。
 *
 * <p>因此在 T-004 这个只读任务里，我也<em>不</em>为 {@code remainingSlots} 提供
 * 任何 setter 之外的写路径——查询接口只读它，不改它。
 */
@TableName("schedule")
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long doctorId;
    private Long departmentId;
    private LocalDate workDate;

    /** 时段：AM / PM。 */
    private String period;

    private Integer totalSlots;

    /** 剩余号源——并发扣减对象，只由原子 UPDATE 修改。 */
    private Integer remainingSlots;

    private BigDecimal fee;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDoctorId() { return doctorId; }
    public void setDoctorId(Long doctorId) { this.doctorId = doctorId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Integer getTotalSlots() { return totalSlots; }
    public void setTotalSlots(Integer totalSlots) { this.totalSlots = totalSlots; }
    public Integer getRemainingSlots() { return remainingSlots; }
    public void setRemainingSlots(Integer remainingSlots) { this.remainingSlots = remainingSlots; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
