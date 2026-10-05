package com.demo.hospital.department.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.department.domain.Department;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 科室持久化（只读）。
 *
 * <p>没有写方法：本期不做管理端，科室由 {@code db/data.sql} 预置
 * （见 docs/01 的"不做清单"）。<b>不预留用不上的方法</b>——
 * 一个空的 {@code update} 会让人以为有地方在改科室数据。
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /**
     * 全部科室，按展示顺序返回。
     *
     * <p>为什么<b>不分页</b>：科室是"字典类"数据，数量固定且很少（本站 5 个）。
     * 给它加分页只会让前端多写一套分页逻辑，而实际永远只有一页。
     * <b>分页应该加在会增长的数据上（排班、挂号记录），不是加在所有列表上。</b>
     *
     * <p>{@code sort_order, id} 两级排序：{@code sort_order} 允许相同，
     * 此时用 {@code id} 兜底，保证<b>顺序稳定</b>——否则两次请求可能返回不同顺序，
     * 前端看起来像"数据在跳"。
     */
    @Select("""
            SELECT id, code, name, description, sort_order, created_at
              FROM department
             ORDER BY sort_order, id
            """)
    List<Department> findAllOrdered();
}
