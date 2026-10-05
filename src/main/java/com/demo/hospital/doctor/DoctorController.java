package com.demo.hospital.doctor;

import com.demo.hospital.doctor.dto.DoctorView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 医生接口。
 *
 * <p>鉴权：需要登录（不在白名单里，由默认拒绝兜住）。
 */
@RestController
@RequestMapping("/api/doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    /**
     * 按科室查医生。
     *
     * <p>⚠️ {@code deptId} 是<b>必填</b>（{@code required = true} 是默认值）。
     * 为什么不给"不传就返回所有医生"的默认行为：
     * 本项目的实际路径永远是"先选科室、再看医生"，
     * 一个"能列全部医生"的接口在本系统里没有真实用途，
     * 却会让"忘了带 deptId"这种 bug 静默地返回一大堆数据而不是报错。
     * <b>只实现真正会被调用的形态。</b>
     *
     * <p>科室不存在时返回 <b>404</b>（而不是空列表），理由见 {@code DoctorService}。
     */
    @GetMapping
    public List<DoctorView> listByDepartment(@RequestParam("deptId") Long deptId) {
        return doctorService.listByDepartment(deptId);
    }
}
