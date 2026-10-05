package com.demo.hospital.doctor.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 医生。
 *
 * <p>只读数据，由 {@code db/data.sql} 预置。
 *
 * <p>注意 {@code departmentId}：医生列表查询会 JOIN 出科室名（见
 * {@code DoctorMapper#findByDepartment}），因为前端列表要显示"哪个科的医生"。
 * 但实体本身只持有外键，不冗余科室名——<b>JOIN 出来的展示字段属于查询结果，
 * 不属于实体</b>，所以它出现在 DTO 里而不是这里。
 */
@TableName("doctor")
public class Doctor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long departmentId;
    private String name;
    private String title;
    private String specialty;
    private String intro;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSpecialty() { return specialty; }
    public void setSpecialty(String specialty) { this.specialty = specialty; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
