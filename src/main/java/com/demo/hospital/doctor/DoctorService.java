package com.demo.hospital.doctor;

import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.dto.DoctorView;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 医生查询。
 */
@Service
public class DoctorService {

    private final DoctorMapper doctorMapper;
    private final DepartmentMapper departmentMapper;

    public DoctorService(DoctorMapper doctorMapper, DepartmentMapper departmentMapper) {
        this.doctorMapper = doctorMapper;
        this.departmentMapper = departmentMapper;
    }

    /**
     * 按科室查医生。
     *
     * <p>⚠️ <b>科室不存在时返回 404，而不是空列表。</b>
     * 这个区分对前端很重要：
     * <ul>
     *   <li>科室不存在 → 404，说明"你进错页面了"，可以引导回科室列表</li>
     *   <li>科室存在但没有医生 → 200 + 空列表，说明"这个科室暂时没医生"，显示空状态</li>
     * </ul>
     * 如果两者都返回空列表，前端就无法区分这两种情况，只能统一显示"暂无数据"——
     * 而用户的下一步操作完全不同。
     */
    public List<DoctorView> listByDepartment(Long departmentId) {
        Department department = departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "科室不存在：" + departmentId);
        }

        List<Doctor> doctors = doctorMapper.findByDepartmentId(departmentId);

        // 该科室下的医生共享同一个科室名，直接复用已查到的 department，
        // 不必再为每行补名字（这正是"先查科室存在性"顺带带来的好处）
        return doctors.stream()
                .map(d -> new DoctorView(
                        d.getId(), d.getName(), d.getTitle(), d.getSpecialty(),
                        d.getDepartmentId(), department.getName()))
                .toList();
    }
}
