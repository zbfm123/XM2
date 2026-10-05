package com.demo.hospital.appointment;

import com.demo.hospital.appointment.domain.Appointment;
import com.demo.hospital.appointment.domain.AppointmentStatus;
import com.demo.hospital.appointment.dto.AppointmentView;
import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.appointment.mapper.AppointmentRow;
import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import com.demo.hospital.common.PageResult;
import com.demo.hospital.schedule.domain.Schedule;
import com.demo.hospital.schedule.mapper.ScheduleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 挂号（下单、取消、列表）。
 *
 * <p>这个类是 T-007 / T-008 / T-009 的落点，也是本项目"防超卖"与"幂等"两条纪律的交汇处。
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 待支付超时时间的默认值（分钟）。application.yml 里有同名配置，两者保持一致。 */
    private static final int DEFAULT_PAYMENT_TIMEOUT_MINUTES = 15;

    private final AppointmentMapper appointmentMapper;
    private final ScheduleMapper scheduleMapper;

    public AppointmentService(AppointmentMapper appointmentMapper, ScheduleMapper scheduleMapper) {
        this.appointmentMapper = appointmentMapper;
        this.scheduleMapper = scheduleMapper;
    }

    // ==================================================================
    // T-007 提交挂号
    // ==================================================================

    /**
     * 提交挂号（幂等）。
     *
     * <h2>执行顺序，以及每一步为什么在这个位置</h2>
     *
     * <ol>
     *   <li><b>先查幂等键</b>——同一个键重复提交时直接返回已有订单，
     *       连号源都不去碰。这是快路径，覆盖"用户双击/网络重试"这个绝大多数场景。</li>
     *   <li><b>再查"同一排班是否已约"</b>——给出友好提示（而不是等数据库抛异常）。</li>
     *   <li><b>原子扣号源</b>——受影响行数为 0 即号源已满，直接失败，<b>不补偿</b>。</li>
     *   <li><b>插入订单</b>——唯一索引在此兜底。</li>
     * </ol>
     *
     * <h2>为什么第 1、2 步的"先查"是安全的，而号源扣减的"先查"是致命的</h2>
     *
     * 这是个容易被混为一谈的地方，值得说清楚：
     * <ul>
     *   <li>幂等键与"同一排班"的<b>先查只是为了让提示更友好</b>。
     *       即使并发绕过了它，唯一索引也会拦住，最终数据仍然正确。
     *       <b>失败方式是多一个异常、少一个提示，不是数据错。</b></li>
     *   <li>号源的"先查再扣"<b>没有第二道防线</b>（数据库不会替我们数号源），
     *       所以一旦有窗口就是真的超卖。因此它必须是单条原子语句。</li>
     * </ul>
     * 换句话说：<b>能不能"先查"，取决于查错了有没有人兜底。</b>
     *
     * @param userId         当前登录用户
     * @param scheduleId     排班 id
     * @param idempotencyKey 幂等键，由客户端提供
     * @param paymentTimeoutMinutes 待支付超时分钟数；≤0 时用默认值
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AppointmentView book(Long userId, Long scheduleId, String idempotencyKey,
                                int paymentTimeoutMinutes) {

        // ---- ① 幂等快路径 ----
        Appointment existing = appointmentMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            log.info("幂等命中，返回已有订单: key={} no={}", idempotencyKey, existing.getAppointmentNo());
            return viewOf(userId, existing.getAppointmentNo(), true);
        }

        // ---- ② 同一排班不可重复挂号（友好提示；最终由唯一索引保证） ----
        Appointment sameSchedule = appointmentMapper.findActiveByUserAndSchedule(userId, scheduleId);
        if (sameSchedule != null) {
            throw new BusinessException(ErrorCode.ALREADY_BOOKED,
                    "您已挂过该排班的号：" + sameSchedule.getAppointmentNo());
        }

        // ---- ③ 校验排班存在 ----
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "排班不存在：" + scheduleId);
        }

        // ---- ④ 原子扣号源（本项目技术内核，见 T-006） ----
        int deducted = scheduleMapper.tryDeduct(scheduleId);
        if (deducted == 0) {
            // 没抢到就是没抢到。**不做任何补偿，也不重试**——
            // 重试只会在同一瞬间制造更多无效争抢，而结果依然是失败。
            throw new BusinessException(ErrorCode.NO_SLOTS_AVAILABLE, "该时段号源已约满");
        }

        // ---- ⑤ 建订单 ----
        Appointment appointment = new Appointment();
        appointment.setAppointmentNo(generateAppointmentNo());
        appointment.setUserId(userId);
        appointment.setScheduleId(scheduleId);
        appointment.setDoctorId(schedule.getDoctorId());
        appointment.setDepartmentId(schedule.getDepartmentId());
        appointment.setIdempotencyKey(idempotencyKey);
        appointment.setVisitDate(schedule.getWorkDate());
        appointment.setPeriod(schedule.getPeriod());
        appointment.setFee(schedule.getFee());
        appointment.setStatus(AppointmentStatus.PENDING_PAYMENT);
        int timeout = paymentTimeoutMinutes > 0 ? paymentTimeoutMinutes : DEFAULT_PAYMENT_TIMEOUT_MINUTES;
        appointment.setExpireAt(LocalDateTime.now().plusMinutes(timeout));

        try {
            appointmentMapper.insert(appointment);
        } catch (DuplicateKeyException e) {
            // ⚠️ 走到这里说明第 1 或第 2 步被并发绕过了。两种唯一索引的处理方式**不同**：
            //
            //   ① uk_appointment_idem（幂等键）：说明同一个请求并发进来了两次。
            //      这不是错误——用户的本意就是"重试这一次提交"。
            //      正确响应是**返回第一次创建的订单**。但号源已经被我们多扣了一个，
            //      **必须归还**，否则号源会凭空少一个（等于系统性地丢号）。
            //
            //   ② uk_appointment_user_schedule：同一个人抢同一个排班。
            //      这是业务拒绝，同样要归还号源。
            //
            // 两种情况的共同点是：**这个事务里已经扣掉的号源必须还回去。**
            // 归还可以放心执行，因为它是带 `remaining < total` 上界的原子语句（T-006 已验证）。
            int returned = scheduleMapper.tryReturn(scheduleId);
            if (returned == 0) {
                // 归还失败意味着号源已经满了 —— 说明"我们多扣的那个"其实不存在，
                // 或者有别的并发写入。这种情况不该静默吞掉，必须留下记录。
                log.error("唯一索引冲突后归还号源失败（返回 0），请人工核查: scheduleId={} userId={} key={}",
                        scheduleId, userId, idempotencyKey, e);
            }

            if (isIdempotencyKeyConflict(e)) {
                Appointment winner = appointmentMapper.findByIdempotencyKey(idempotencyKey);
                if (winner != null) {
                    log.info("并发重复提交，归还号源并返回先到者的订单: key={} no={}",
                            idempotencyKey, winner.getAppointmentNo());
                    return viewOf(userId, winner.getAppointmentNo(), true);
                }
            }

            Appointment sameSlot = appointmentMapper.findActiveByUserAndSchedule(userId, scheduleId);
            if (sameSlot != null) {
                throw new BusinessException(ErrorCode.ALREADY_BOOKED,
                        "您已挂过该排班的号：" + sameSlot.getAppointmentNo());
            }
            // 兜底：唯一索引冲突了，但用两种键都查不到冲突对象 —— 数据异常，不能假装成功
            throw new BusinessException(ErrorCode.ALREADY_BOOKED, "该挂号已存在");
        }

        return viewOf(userId, appointment.getAppointmentNo(), false);
    }

    // ==================================================================
    // T-008 取消挂号
    // ==================================================================

    /**
     * 取消挂号并归还号源。
     *
     * <h2>两个必须处理的细节</h2>
     *
     * <p><b>① 状态迁移必须是"带起始状态的原子 UPDATE"。</b>
     * 先查出状态、判断、再更新，在"查"和"改"之间订单可能已被支付 ——
     * 结果是<b>一笔已付款的订单被取消，号源还被归还了</b>。
     * 所以用 {@code transitionStatus(..., fromStatus, ...)}，
     * 受影响行数为 0 就说明状态已经不是我们以为的那个了。
     *
     * <p><b>② 只有真正改变了状态的那一次才归还号源。</b>
     * 重复取消不能重复归还，否则号源会被加到超过总数
     * （上界判断会挡住，但"这次取消到底有没有生效"必须由状态迁移的结果决定，
     * 不能靠"尝试归还"来推断）。
     *
     * <p><b>③ 已支付/已完成的订单按状态机拒绝。</b>
     * {@code COMPLETED} 是终态无出边；{@code PAID} 可以取消（业务上允许退号），
     * 但这里<b>不允许</b>——见方法内的说明。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AppointmentView cancel(Long userId, String appointmentNo, String reason) {
        Appointment appointment = appointmentMapper.findByNoAndUser(appointmentNo, userId);
        if (appointment == null) {
            // 用 404 而不是 403：对调用方而言"不是你的单"和"不存在"应当无法区分，
            // 否则这个接口就成了"枚举别人单号"的工具。
            throw new BusinessException(ErrorCode.NOT_FOUND, "挂号单不存在：" + appointmentNo);
        }

        AppointmentStatus from = appointment.getStatus();

        // ---- ① 已取消：幂等成功 ----
        // A-05 明确要求"重复取消幂等"。用户第二次点取消（或前端自动重试）时，
        // 他的目标——"这张单被取消"——已经达成了。此时返回 409 只会让前端
        // 多写一个无意义的分支，而且让"取消成功但响应丢失、用户又点了一次"
        // 这个常见场景变成一条报错。
        if (from == AppointmentStatus.CANCELLED) {
            log.info("重复取消，按幂等成功处理: no={}", appointmentNo);
            return viewOf(userId, appointmentNo, true);
        }

        // ---- ② 其它终态：拒绝，并说清当前是什么状态 ----
        // 只说"不能取消"会让用户以为系统坏了；给出当前状态他才知道该找谁。
        if (from.isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "当前状态不可取消：" + from);
        }

        // ---- ③ 业务决定：只有"待支付"可以取消 ----
        // PAID 虽然状态机允许迁移到 CANCELLED，但退号涉及退费，
        // 而本项目明确不做真实退费（见 docs/01 的"不做清单"）。
        // 与其做一个"取消了但不退钱"的接口，不如明确不做。
        if (from != AppointmentStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "已支付的挂号暂不支持在线取消，请联系医院：" + from);
        }

        int changed = appointmentMapper.transitionStatus(
                appointment.getId(), AppointmentStatus.PENDING_PAYMENT,
                AppointmentStatus.CANCELLED, reason);

        if (changed == 0) {
            // 状态在我们读取之后被改掉了（例如延迟队列刚好把它取消了）。
            // 这种并发不是错误：用户的目标（这张单被取消）已经达成。
            // 重新读一次，若确实已是终态就按幂等成功返回。
            Appointment latest = appointmentMapper.findByNoAndUser(appointmentNo, userId);
            if (latest != null && latest.getStatus() == AppointmentStatus.CANCELLED) {
                log.info("并发取消，按幂等成功处理: no={}", appointmentNo);
                return viewOf(userId, latest.getAppointmentNo(), true);
            }
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "订单状态已变更，取消未生效，请刷新后重试");
        }

        // ⚠️ 归还号源只在"状态确实被我们改掉了"这一次执行。
        // 重复取消会在上面因状态已是 CANCELLED（终态）而被拒绝，走不到这里。
        int returned = scheduleMapper.tryReturn(appointment.getScheduleId());
        if (returned == 0) {
            // 还不上号源通常意味着数据异常（例如号源已满）。记 error 供排查，
            // 但**不让整个取消失败**：用户的目标是"取消订单"，那个已经达成了。
            // 号源问题属于需要修的运维问题，不该把用户的操作回滚掉。
            log.error("取消成功但号源归还失败（受影响行数 0）: no={} scheduleId={}",
                    appointmentNo, appointment.getScheduleId());
        }

        // 用统一出口回显，保证"取消"的响应里也带医生名与科室名
        return viewOf(userId, appointmentNo, false);
    }

    // ==================================================================
    // T-009 我的挂号列表
    // ==================================================================

    /**
     * 我的挂号列表（分页、可按状态筛选）。
     *
     * <p>⚠️ {@code userId} 是<b>查询条件的一部分</b>，不是可选的过滤项。
     * 它由控制器从登录上下文取出后强制传入，用户无法通过参数指定别人的 id——
     * 这是本项目"只能看到自己的"（F-03-6）的实现方式：
     * <b>不是"查出来再过滤"，而是"查询本身就只可能查到自己的"。</b>
     */
    public PageResult<AppointmentView> listMine(Long userId, AppointmentStatus status,
                                            int page, int size) {
        if (page < 1) {
            throw BusinessException.invalidParameter("页码必须从 1 开始，收到：" + page);
        }
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int offset = (page - 1) * safeSize;

        long total = appointmentMapper.countByUser(userId, status);
        var rows = appointmentMapper.findPageRowsByUser(userId, status, safeSize, offset);
        return PageResult.of(rows.stream().map(r -> toView(r, false)).toList(), total, page, safeSize);
    }

    // ==================================================================

    /**
     * 生成本项目的业务单号。
     *
     * <p>格式：{@code AP + yyyyMMddHHmmss + 4 位随机数}。
     *
     * <p>为什么不用自增 id 对外：<b>自增 id 会把业务量暴露出去</b>——
     * 竞品下两单就能算出你一天的订单量。
     *
     * <p>为什么不用 UUID：单号是要念给窗口工作人员听的，32 位十六进制不现实。
     *
     * <p>⚠️ 这里<b>不保证绝对唯一</b>，也不试图保证：
     * 唯一性由 {@code uk_appointment_no} 唯一索引兜底，冲突会抛
     * {@code DuplicateKeyException}（概率极低，且已被上面的事务逻辑正确处理）。
     * <b>与其在应用层写一个"看起来很严谨"的查重循环，不如让数据库来保证。</b>
     */
    private String generateAppointmentNo() {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(LocalDateTime.now());
        int random = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "AP" + stamp + random;
    }

    /**
     * 判断这个唯一索引冲突是不是幂等键引起的。
     *
     * <p>依赖异常消息里包含索引名。这确实不如结构化判断干净，
     * 但 JDBC 抛出的异常本身不携带"哪个约束"的结构化信息。
     * 因此这里的策略是：<b>按索引名判断，判断不出来时再去查库确认</b>——
     * 调用方的处理逻辑对"查得到就返回、查不到就报错"是安全的，
     * 不依赖这个判断绝对准确。
     */
    private boolean isIdempotencyKeyConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage()
                : e.getMessage();
        return message != null && message.contains("uk_appointment_idem");
    }

    /**
     * 读模型 → 视图。
     *
     * <p>之所以只保留这一个转换方法（而不是"实体 → 视图"+"读模型 → 视图"两个）：
     * <b>对外返回的每一处都必须带医生名与科室名</b>，否则前端就得自己补查。
     * 只留一条路径，就不可能出现"这个接口忘了带名字"的不一致。
     */
    private AppointmentView toView(AppointmentRow r, boolean replayed) {
        return new AppointmentView(
                r.appointmentNo(), r.scheduleId(), r.doctorName(), r.departmentName(),
                r.visitDate(), r.period(), r.fee(), r.status(),
                r.expireAt(), r.cancelReason(), r.createdAt(), replayed);
    }

    /**
     * 统一出口：按单号取出带展示字段的视图。
     *
     * <p>查不到时回退为"只有单号"的最小视图。回退是安全的，因为调用点都发生在
     * 刚写入或刚改状态之后——数据一定存在，取不到只可能是极端的并发删除。
     * <b>即便如此也不返回 null</b>：接口返回一个字段不全的对象，
     * 比返回 null 让前端抛异常更容易排查。
     */
    private AppointmentView viewOf(Long userId, String appointmentNo, boolean replayed) {
        AppointmentRow row = appointmentMapper.findRowByNoAndUser(appointmentNo, userId);
        if (row != null) {
            return toView(row, replayed);
        }
        log.warn("未能取到订单展示信息，返回最小视图: no={}", appointmentNo);
        return new AppointmentView(appointmentNo, null, null, null,
                null, null, null, null, null, null, null, replayed);
    }
}
