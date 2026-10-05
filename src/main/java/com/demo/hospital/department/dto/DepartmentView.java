package com.demo.hospital.department.dto;

/**
 * 科室列表项。
 *
 * <p>只暴露前端渲染列表需要的字段；{@code code} 保留是因为演示"按科室编码"
 * 这类场景时有用，且它不是敏感信息。
 */
public record DepartmentView(
        Long id,
        String code,
        String name,
        String description
) {
}
