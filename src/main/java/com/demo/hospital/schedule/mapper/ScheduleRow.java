package com.demo.hospital.schedule.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 排班列表的<b>读模型</b>（read model），直接对应"前端要看到的一行"。
 *
 * <p>为什么用构造器映射的 record 而不是"实体 + 若干次补查"：
 * <ul>
 *   <li>一条 JOIN 取完医生名、职称、科室名，<b>没有 N+1</b></li>
 *   <li>字段顺序与 SQL 的 SELECT 顺序一一对应，映射是显式的，
 *       不依赖"名字碰巧对上"的隐式行为</li>
 * </ul>
 *
 * <p>⚠️ 映射依赖 {@code ScheduleMapper} 里的<b>显式列别名</b>（别名 = 本 record 的字段名）。
 * 不要退回成裸的 {@code d.name, dept.name}：医生表与科室表都有 {@code name} 列，
 * 结果集里两个同名标签会让构造器映射选错，而且不报错——
 * 本查询最初就是这样，表现为"科室名显示成了医生名"。
 *
 * <p>注意这里<b>没有 setter、也没有实体那些时间戳字段</b>——
 * 它只是查询结果的载体，不是一个可以拿来改的数据对象。
 */
public record ScheduleRow(
        Long id,
        Long doctorId,
        String doctorName,
        String doctorTitle,
        Long departmentId,
        String departmentName,
        LocalDate workDate,
        String period,
        Integer totalSlots,
        Integer remainingSlots,
        BigDecimal fee
) {
}
