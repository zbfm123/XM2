package com.demo.hospital.doctor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.doctor.domain.Doctor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 医生持久化（只读）。
 *
 * <p>本接口的查询刻意<b>不 JOIN 科室表</b>：JOIN 出来的科室名是"展示字段"，
 * 由 Service 层批量补齐（见 {@code DoctorService}）。
 * 这样 Mapper 的职责保持单一——它只负责把 doctor 表的行取出来，
 * 而"返回体长什么样"由 Service 决定。
 *
 * <p>这与"能不能一次 JOIN 查完"无关，是**可测试性**的取舍：
 * Mapper 返回实体时，任何字段映射错误都会在集成测试里立刻暴露；
 * 而返回拼装好的 DTO 时，问题往往变成"某个字段是 null"这种难查的现象。
 */
@Mapper
public interface DoctorMapper extends BaseMapper<Doctor> {

    /** 某科室下的医生，按 id 排序（预置数据的顺序即录入顺序，稳定可预期）。 */
    @Select("""
            SELECT id, department_id, name, title, specialty, intro, created_at
              FROM doctor
             WHERE department_id = #{departmentId}
             ORDER BY id
            """)
    List<Doctor> findByDepartmentId(@Param("departmentId") Long departmentId);
}
