package com.demo.hospital.doctor.dto;

/**
 * 医生列表项（含所属科室名）。
 *
 * <p>为什么单独建 DTO 而不是直接返回实体 + 让前端再查一次科室名：
 * 医生列表页要显示"张伟民 · 内科 · 主任医师"，如果只给 {@code departmentId}，
 * 前端就得为每一行再发一次请求（或先把科室列表缓存起来做本地 join）。
 * <b>一次 JOIN 能解决的事，不该变成前端的 N+1 次请求。</b>
 *
 * <p>{@code departmentName} 属于"查询结果的展示字段"，不属于医生实体——
 * 它由 {@code DoctorMapper} 的 JOIN 提供，所以只存在于这个 DTO 里。
 *
 * @param id              医生 id
 * @param name            姓名（演示数据为虚构）
 * @param title           职称
 * @param specialty       擅长
 * @param departmentId    所属科室 id
 * @param departmentName  所属科室名（JOIN 得到）
 */
public record DoctorView(
        Long id,
        String name,
        String title,
        String specialty,
        Long departmentId,
        String departmentName
) {
}
