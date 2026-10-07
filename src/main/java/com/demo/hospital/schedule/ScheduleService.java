package com.demo.hospital.schedule;

import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import com.demo.hospital.common.PageResult;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.schedule.dto.ScheduleView;
import com.demo.hospital.schedule.mapper.ScheduleMapper;
import com.demo.hospital.schedule.mapper.ScheduleRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班（号源）查询。
 *
 * <p>⚠️ <b>本类只读号源，绝不修改它。</b>扣减属于 T-006，且必须是一条原子 UPDATE。
 * 在这个只读任务里，{@code remainingSlots} 的唯一作用是展示。
 *
 * <p><b>查询结果走 Redis 缓存</b>（见 {@link com.demo.hospital.config.CacheConfig}）。
 * 选它做缓存点是因为"读多写少"——所有人都在查排班，只有挂号/取消时才写。
 * ⚠️ 但缓存<b>不参与扣减</b>：能不能挂上号由数据库那条原子 UPDATE 决定，
 * 所以缓存再旧也不可能多卖一个号。
 */
@Service
public class ScheduleService {

    /** 每页条数上限。 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ScheduleMapper scheduleMapper;
    private final DoctorMapper doctorMapper;

    public ScheduleService(ScheduleMapper scheduleMapper, DoctorMapper doctorMapper) {
        this.scheduleMapper = scheduleMapper;
        this.doctorMapper = doctorMapper;
    }

    /**
     * 按医生查排班（分页，可按日期区间过滤）。
     *
     * <p><b>页码是 1 基</b>（对外契约）。这里做一次、也只做一次 0 基转换：
     * {@code offset = (page - 1) * size}。
     * Mapper 只接受算好的 limit/offset，所以不可能出现"两处各转一次"的错位。
     *
     * <p>医生不存在时抛 404 而不是返回空页——理由与科室相同（见 {@code DoctorService}）：
     * "医生不存在"和"医生没排班"对用户的意义完全不同。
     */
@org.springframework.cache.annotation.Cacheable(
            cacheNames = com.demo.hospital.config.CacheConfig.SCHEDULE_CACHE,
            key = "{#doctorId, #from, #to, #page, #size}")
    public PageResult<ScheduleView> listByDoctor(Long doctorId, LocalDate from, LocalDate to,
                                                 int page, int size) {
        if (doctorMapper.selectById(doctorId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "医生不存在：" + doctorId);
        }

        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        int offset = (safePage - 1) * safeSize;   // ← 唯一的 1 基 → 0 基转换点

        long total = scheduleMapper.countByDoctor(doctorId, from, to);
        List<ScheduleRow> rows = scheduleMapper.findPageByDoctor(doctorId, from, to, safeSize, offset);

        List<ScheduleView> items = rows.stream().map(ScheduleService::toView).toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    /**
     * 页码校验：小于 1 直接报错。
     *
     * <p>这里刻意<b>不</b>把 {@code page=0} 静默纠正成第 1 页。
     * 曾经的写法是"悄悄修正"，但那会掩盖真正的调用错误：
     * 当分页器在第 2 页显示了第 1 页的数据时，问题会被藏起来，
     * 直到有人盯着页码和数据比对才发现。
     * <b>参数错了就说出来，前端才能修正它。</b>
     */
    private int normalizePage(int page) {
        if (page < 1) {
            throw BusinessException.invalidParameter("页码必须从 1 开始，收到：" + page);
        }
        return page;
    }

    /** 每页条数兜底：太大或太小都收敛到合理范围，避免一次查出整个表。 */
    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 读模型 → 对外视图。
     *
     * <p>{@code soldOut} 在这里由剩余号源推导，而不是打进 SQL：
     * 它是一个<b>业务判断</b>（"剩余 &le; 0 即已约满"），
     * 把它放在 Java 里才能被单元测试直接覆盖，也才能让"什么算约满"只有一处定义。
     */
    static ScheduleView toView(ScheduleRow r) {
        return new ScheduleView(
                r.id(), r.doctorId(), r.doctorName(), r.doctorTitle(),
                r.departmentId(), r.departmentName(),
                r.workDate(), r.period(),
                r.totalSlots(), r.remainingSlots(), r.fee(),
                ScheduleView.computeSoldOut(r.remainingSlots()));
    }
}
