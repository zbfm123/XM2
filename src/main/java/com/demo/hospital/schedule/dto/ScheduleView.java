package com.demo.hospital.schedule.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 排班（号源）列表项。
 *
 * <p>这是 T-004 里信息量最大的一个返回体，因为**它是"挂号"这个动作的输入**：
 * 前端要拿它显示"哪天/上午下午/还有几个号/多少钱"，并且据此决定挂号按钮是否可点。
 *
 * @param id              排班 id（挂号时提交的就是它）
 * @param doctorId        医生 id
 * @param doctorName      医生姓名（JOIN 得到）
 * @param doctorTitle     医生职称（JOIN 得到；前端常按职称排序展示）
 * @param departmentId    科室 id
 * @param departmentName  科室名（JOIN 得到）
 * @param workDate        出诊日期
 * @param period          时段：AM / PM
 * @param totalSlots      总号源
 * @param remainingSlots  <b>剩余号源</b>——防超卖的直接观测对象（需求 F-03-1）
 * @param fee             挂号费
 * @param soldOut         是否已约满
 */
public record ScheduleView(
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
        BigDecimal fee,
        boolean soldOut
) {

    /**
     * 由各字段推导出"是否已约满"。
     *
     * <p>为什么在服务端算这个布尔值，而不是让前端判断 {@code remainingSlots == 0}：
     * 这是 T-016 明确要求的行为——<b>"号源为 0 时按钮禁用并显示已约满，
     * 不要让用户点了才知道"</b>。把它做成返回体里的一个字段，
     * 前端就不必自己理解"什么算约满"，将来若有"停诊"之类的状态也只需改这一处。
     *
     * <p>用 {@code <= 0} 而不是 {@code == 0}：号源**不允许为负**（防超卖的断言之一），
     * 但万一真出现了负数，它显然也应该是"已约满"，而不是显示一个负数剩余。
     */
    public static boolean computeSoldOut(Integer remainingSlots) {
        return remainingSlots == null || remainingSlots <= 0;
    }
}
