package com.demo.hospital.schedule;

import com.demo.hospital.common.PageResult;
import com.demo.hospital.schedule.dto.ScheduleView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 排班（号源）接口。
 *
 * <p>鉴权：需要登录（不在白名单里，由默认拒绝兜住）。
 *
 * <p>⚠️ 这是本项目<b>唯一带分页</b>的查询接口，而且它返回的
 * {@code remainingSlots} 就是 T-006 防超卖要保护的那个值——
 * 前端据此显示"剩 3 个号"或"已约满"。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * 按医生查排班。
     *
     * @param doctorId 医生 id（必填）
     * @param from     起始日期，格式 {@code yyyy-MM-dd}，可选
     * @param to       结束日期，格式 {@code yyyy-MM-dd}，可选
     * @param page     页码，<b>从 1 开始</b>，默认 1
     * @param size     每页条数，默认 10，上限 100
     */
    @GetMapping
    public PageResult<ScheduleView> listByDoctor(
            @RequestParam("doctorId") Long doctorId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return scheduleService.listByDoctor(doctorId, from, to, page, size);
    }
}
