package com.demo.hospital.department;

import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.dto.DepartmentView;
import com.demo.hospital.department.mapper.DepartmentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 科室查询。
 *
 * <p>只有读操作——本期不做管理端，科室由 SQL 预置（docs/01 的"不做清单"）。
 */
@Service
public class DepartmentService {

    private final DepartmentMapper departmentMapper;

    public DepartmentService(DepartmentMapper departmentMapper) {
        this.departmentMapper = departmentMapper;
    }

    /**
     * 全部科室。
     *
     * <p>不分页（理由见 {@link DepartmentMapper#findAllOrdered()}）：
     * 字典类数据给分页只会让前端多写一套永远用不到的逻辑。
     */
    public List<DepartmentView> listAll() {
        return departmentMapper.findAllOrdered().stream().map(DepartmentService::toView).toList();
    }

    /** 实体 → 对外视图。把"出网的形状"与"存储的形状"分开。 */
    static DepartmentView toView(Department d) {
        return new DepartmentView(d.getId(), d.getCode(), d.getName(), d.getDescription());
    }
}
