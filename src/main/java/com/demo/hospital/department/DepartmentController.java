package com.demo.hospital.department;

import com.demo.hospital.department.dto.DepartmentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 科室接口。
 *
 * <p>鉴权：这个路径<b>不在</b> {@code SecurityConfig} 的白名单里，因此<b>需要登录</b>
 * （默认拒绝）。这是有意的——科室、医生、排班都属于业务数据，
 * 虽然"不是秘密"，但让它们跟着登录态走，一方面与本项目"只有一个角色"的模型一致，
 * 另一方面避免了"先放行、以后再收紧"这类最容易漏改的改动。
 *
 * <p>不需要为新增接口改任何安全配置：{@code anyRequest().authenticated()} 已经兜住了。
 */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /**
     * 科室列表。
     *
     * <p>直接返回数组而不是分页对象：科室是字典数据，永远只有一页
     * （理由见 {@code DepartmentMapper#findAllOrdered()}）。
     * 前端因此可以 {@code list.value = data} 直接用，不必解一层 {@code items}。
     */
    @GetMapping
    public List<DepartmentView> list() {
        return departmentService.listAll();
    }
}
