package com.demo.hospital.appointment;

import com.demo.hospital.appointment.domain.Appointment;
import com.demo.hospital.appointment.domain.AppointmentStatus;
import com.demo.hospital.appointment.dto.AppointmentView;
import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.config.AppointmentProperties;
import com.demo.hospital.appointment.mapper.AppointmentRow;
import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import com.demo.hospital.common.PageResult;
import com.demo.hospital.notification.Notifier;
import com.demo.hospital.notification.mq.NotificationMessage;
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
    private final Notifier notifier;
    private final AppointmentProperties properties;

    public AppointmentService(AppointmentMapper appointmentMapper,
                              ScheduleMapper scheduleMapper,
                              Notifier notifier,
                          AppointmentProperties properties) {
        this.appointmentMapper = appointmentMapper;
        this.scheduleMapper = scheduleMapper;
        this.notifier = notifier;
        this.properties = properties;
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
     * @param paymentTimeoutMinutes 待支付超时分钟数（支持小数）；≤0 时用默认值
     */
    @Transactional(noRollbackFor = BusinessException.class)
// ⚠️ 失效缓存的理由：这三个方法都会改变号源数量（扣减或归还），
    //    而号源查询结果正被 Redis 缓存。不失效的话，用户会一直看到旧号源数字。
    //
    // 为什么用 allEntries = true（清空整个排班缓存）而不是精确删某一个医生：
    // 这里是按 scheduleId 操作的，而缓存键是按 doctorId 组织的——
    // **需要多一次查库才能知道该删哪个医生**，为一次失效多查一次库不划算。
    // 排班数据量小（本演示库 140 条），整体清空的代价可以接受。
    //
    // ⚠️ 这个取舍要能说出来：如果排班量很大（比如几十万条），
    //    整体清空会导致缓存频繁被清、命中率暴跌，那时就应该换成
    //    "按医生精确失效"（代价是多一次查询）。**缓存粒度是跟着数据量走的。**
    //
    // ⚠️ @CacheEvict 默认在方法**成功返回后**执行。这三个方法都是 @Transactional 的，
    //    而 Spring 会按"事务通知在前、缓存通知在后"的顺序执行 ——
    //    因此失效发生在**事务提交之后**。这个顺序很重要：
    //    如果先失效后提交，那么在"已失效但还没提交"的窗口里，
    //    别的请求会把**旧值**重新读进缓存，缓存又被污染了。
    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = com.demo.hospital.config.CacheConfig.SCHEDULE_CACHE,
            allEntries = true)
    public AppointmentView book(Long userId, Long scheduleId, String idempotencyKey,
                                double paymentTimeoutMinutes) {

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
        // ⚠️ 用 plusSeconds 而不是 plusMinutes：业务时限要支持秒级，
        //    否则测试里配不出"和队列 TTL 一样短"的时限（详见 AppointmentProperties 的注释）。
        double timeoutMinutes = paymentTimeoutMinutes > 0
                ? paymentTimeoutMinutes
                : DEFAULT_PAYMENT_TIMEOUT_MINUTES;
        appointment.setExpireAt(LocalDateTime.now().plusSeconds((long) Math.round(timeoutMinutes * 60.0)));

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

        // ---- ⑥ 通知与超时调度：尽力而为（决策 D-06 / 验收 A-07） ----
        //
        // ⚠️ 这里是本项目**最容易被写错的一处**，值得把话说透。
        //
        // 【为什么放在扣号源与建订单**之后**】
        //   如果先发消息再扣号源，那么"消息发出去了但号源扣失败"会让用户
        //   收到一条"预约成功"的通知，而实际上他没挂上号。
        //   通知是对**已完成的事实**的描述，所以必须在事实确立之后发生。
        //
        // 【为什么 notifier 内部一定要吞掉异常】
        //   用户已经挂上号了：号源扣了、订单建了。这时候 broker 连不上，
        //   是"通知发不出去"，不是"挂号失败"。
        //   把这两件事混在一起的结果就是——**MQ 一抖动，用户就挂不上号**。
        //   这正是 A-07 要验证的纪律，也是与项目 1"AI 挂了规则不受影响"的同一种思路。
        //
        // 【为什么不做成强一致（本地消息表 + 发布确认）】
        //   那属于"必须送达"才值得的复杂度。通知在本项目里是尽力而为：
        //   发不出去就记 error 日志等补偿，绝不能让一条通知决定一笔挂号能否成立。
        //
        // ⚠️ 已知边界（面试主动交代）：消息在事务提交前发出，因此存在一个极小的窗口——
        //   消息已投递但事务随后回滚，消费者会为一张不存在的订单写通知。
        //   彻底解决要"事务提交后再发"（本地消息表或 TransactionSynchronization），
        //   本期时间不允许，已在 docs/PROGRESS.md 记为已知限制。
        boolean notified = tryNotifyBooked(appointment, schedule);
        boolean scheduled = tryScheduleTimeout(appointment.getAppointmentNo());

        if (!notified || !scheduled) {
            // 只记日志，**不改业务结果**。返回给用户的仍然是"挂号成功"。
            log.error("挂号成功但异步投递未完全成功（业务不受影响，待补偿）: no={} notified={} scheduled={}",
                    appointment.getAppointmentNo(), notified, scheduled);
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
// ⚠️ 失效缓存的理由：这三个方法都会改变号源数量（扣减或归还），
    //    而号源查询结果正被 Redis 缓存。不失效的话，用户会一直看到旧号源数字。
    //
    // 为什么用 allEntries = true（清空整个排班缓存）而不是精确删某一个医生：
    // 这里是按 scheduleId 操作的，而缓存键是按 doctorId 组织的——
    // **需要多一次查库才能知道该删哪个医生**，为一次失效多查一次库不划算。
    // 排班数据量小（本演示库 140 条），整体清空的代价可以接受。
    //
    // ⚠️ 这个取舍要能说出来：如果排班量很大（比如几十万条），
    //    整体清空会导致缓存频繁被清、命中率暴跌，那时就应该换成
    //    "按医生精确失效"（代价是多一次查询）。**缓存粒度是跟着数据量走的。**
    //
    // ⚠️ @CacheEvict 默认在方法**成功返回后**执行。这三个方法都是 @Transactional 的，
    //    而 Spring 会按"事务通知在前、缓存通知在后"的顺序执行 ——
    //    因此失效发生在**事务提交之后**。这个顺序很重要：
    //    如果先失效后提交，那么在"已失效但还没提交"的窗口里，
    //    别的请求会把**旧值**重新读进缓存，缓存又被污染了。
    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = com.demo.hospital.config.CacheConfig.SCHEDULE_CACHE,
            allEntries = true)
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

        // ---- 通知：尽力而为，与下单同一条纪律（A-07） ----
        // 同样在业务动作**之后**发，且失败不影响取消结果。
        boolean cancelNotified;
        try {
            cancelNotified = notifier.notifyCancelled(toCancelledMessage(appointment));
        } catch (Exception e) {
            // 与下单同一条纪律：实现违约抛异常，也不能拖垮取消
            log.error("取消通知投递抛出异常（已兜住，业务不受影响）: no={}", appointmentNo, e);
            cancelNotified = false;
        }
        if (!cancelNotified) {
            log.error("取消成功但通知投递失败（业务不受影响，待补偿）: no={}", appointmentNo);
        }

        // 用统一出口回显，保证"取消"的响应里也带医生名与科室名
        return viewOf(userId, appointmentNo, false);
    }

    // ==================================================================
    // T-012 超时自动取消（延迟队列消费者调用）
    // ==================================================================

    /**
     * 待支付超时：取消订单并归还号源。
     *
     * <h2>⚠️ 这里有一个真实的坑，是本任务最关键的判断</h2>
     *
     * <b>只有订单仍然是 {@code PENDING_PAYMENT} 时才允许取消。</b>
     *
     * <p>延迟消息是<b>下单那一刻</b>就发出去的，15 分钟后才回来。
     * 而在这 15 分钟里用户完全可能已经<b>付款</b>了。如果消费者不看状态就取消：
     *
     * <pre>
     *   13:00  下单，发一条 15 分钟延迟消息
     *   13:05  用户支付成功 -> PAID
     *   13:15  延迟消息到期 -> 消费者把 PAID 的订单改成 CANCELLED，并归还号源
     * </pre>
     * 结果是：<b>用户付了钱，号被取消了，而且号源被还回去卖给了别人。</b>
     * 这是最严重的一类数据不一致——它同时伤害了用户和医院的账。
     *
     * <p>所以取消动作必须是<b>带起始状态条件的原子 UPDATE</b>：
     * 受影响行数为 0 就说明"它已经不是我该取消的那个状态了"，
     * 此时<b>什么都不做</b>（既不报错，也不归还号源）。
     *
     * <p>这也是"用延迟队列做定时"的通用纪律：
     * <b>延迟消息只能表达"到了该检查的时间"，不能表达"到点就该执行"。</b>
     * 到点之后该做什么，必须重新判断一次。
     *
     * @return {@code true} = 本次确实取消了；{@code false} = 订单已不是待支付（什么都不做）
     */
    @Transactional(noRollbackFor = BusinessException.class)
// ⚠️ 失效缓存的理由：这三个方法都会改变号源数量（扣减或归还），
    //    而号源查询结果正被 Redis 缓存。不失效的话，用户会一直看到旧号源数字。
    //
    // 为什么用 allEntries = true（清空整个排班缓存）而不是精确删某一个医生：
    // 这里是按 scheduleId 操作的，而缓存键是按 doctorId 组织的——
    // **需要多一次查库才能知道该删哪个医生**，为一次失效多查一次库不划算。
    // 排班数据量小（本演示库 140 条），整体清空的代价可以接受。
    //
    // ⚠️ 这个取舍要能说出来：如果排班量很大（比如几十万条），
    //    整体清空会导致缓存频繁被清、命中率暴跌，那时就应该换成
    //    "按医生精确失效"（代价是多一次查询）。**缓存粒度是跟着数据量走的。**
    //
    // ⚠️ @CacheEvict 默认在方法**成功返回后**执行。这三个方法都是 @Transactional 的，
    //    而 Spring 会按"事务通知在前、缓存通知在后"的顺序执行 ——
    //    因此失效发生在**事务提交之后**。这个顺序很重要：
    //    如果先失效后提交，那么在"已失效但还没提交"的窗口里，
    //    别的请求会把**旧值**重新读进缓存，缓存又被污染了。
    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = com.demo.hospital.config.CacheConfig.SCHEDULE_CACHE,
            allEntries = true)
    public boolean cancelOnTimeout(String appointmentNo, String reason) {
        Appointment appointment = appointmentMapper.findByNo(appointmentNo);
        if (appointment == null) {
            // 订单不存在（可能已被清理）。不抛异常：这是定时任务，
            // 一条处理不掉的消息不该让消费者反复重投。
            log.warn("超时取消：订单不存在，忽略: no={}", appointmentNo);
            return false;
        }

        // 已经是终态（已取消/已完成）或已支付 —— 一律不动它。
        if (appointment.getStatus() != AppointmentStatus.PENDING_PAYMENT) {
            log.info("超时取消：订单当前状态为 {}，不属于待支付，跳过（这是正确行为）: no={}",
                    appointment.getStatus(), appointmentNo);
            return false;
        }

        // ==============================================================
        // ⚠️ 再判断一次"到底到点了没有"
        // ==============================================================
        //
        // 【为什么必须有这一层】延迟消息的 TTL 由**队列参数**决定，
        // 而队列参数在队列**首次声明时就固定了**——改了配置也不会作用于已存在的队列。
        // 也就是说，"消息什么时候投递过来"这个时间点**不保证**等于
        // "订单的支付时限"（app.appointment.payment-ttl-millis）。
        //
        // 两者一旦不一致会怎样：
        //   · 队列 TTL 比业务时限**短** -> 订单会被**提前取消**（用户还在付款就没了）
        //   · 队列 TTL 比业务时限**长** -> 订单晚取消一会儿（可接受，但语义不一致）
        //
        // 【实测踩到的坑】做 A-06 彩排时想把等待时间缩短，用
        // APP_APPOINTMENT_PAYMENT_TTL_MILLIS=15000 启动，结果延迟队列的
        // x-message-ttl 仍然是 900000（队列早就声明过了）——
        // 而业务侧 expire_at 已经变成 15 秒。两个数字就此**分家**。
        //
        // 【结论】唯一权威的时间判据是**订单自己的 expire_at**。
        // 队列 TTL 只当作"投递延迟"，业务规则必须自己说了算。
        //
        // 这一层与上面的状态判断**各管一件事**，两者都要有：
        //   · 状态判断 -> 防"已支付的被取消"
        //   · 时间判断 -> 防"还没到点就被取消"
        //
        // 注意：这里把"没到点"记成 **WARN** 而不是 INFO ——
        // 它意味着队列 TTL 与业务时限不一致，是**配置问题**，
        // 需要人看一眼。正常的"已支付所以跳过"才该是 INFO。
        LocalDateTime expireAt = appointment.getExpireAt();
        if (expireAt == null) {
            // 防御：正常情况下 expire_at 一定有值（下单时写入）。
            // 真为 null 说明数据被外部改坏了——按业务时限从创建时间推算，
            // 而不是"直接取消"（宁可晚取消，不可早取消）。
            expireAt = appointment.getCreatedAt() == null
                    ? LocalDateTime.now()
                    : appointment.getCreatedAt()
                            .plusSeconds((long) Math.round(properties.paymentTimeoutMinutes() * 60.0));
            log.warn("超时取消：订单缺少 expire_at，按业务时限从创建时间推算: no={} 推算={}",
                    appointmentNo, expireAt);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(expireAt)) {
            log.warn("超时取消：**尚未到支付时限**，跳过。"
                            + "这说明延迟队列的 TTL 比业务时限短（配置不一致，请检查）: "
                            + "no={} 现在={} 时限={} 还差 {} ms",
                    appointmentNo, now, expireAt,
                    java.time.Duration.between(now, expireAt).toMillis());
            return false;
        }

        // 带起始状态的原子 UPDATE：与用户主动取消走同一条路径、同一套保护
        int changed = appointmentMapper.transitionStatus(
                appointment.getId(), AppointmentStatus.PENDING_PAYMENT,
                AppointmentStatus.CANCELLED, reason);

        if (changed == 0) {
            // 在"读"与"改"之间状态被改了（用户刚好付款，或用户自己取消了）。
            // ⚠️ 这种并发不是错误，而且**绝不能归还号源**——
            //    号源该不该还，取决于这次取消到底有没有生效。
            log.info("超时取消：状态已被并发修改，本次不生效: no={}", appointmentNo);
            return false;
        }

        // 只有真正改变了状态的那一次才归还号源（与用户主动取消同一条纪律）
        int returned = scheduleMapper.tryReturn(appointment.getScheduleId());
        if (returned == 0) {
            log.error("超时取消成功但号源归还失败（受影响行数 0）: no={} scheduleId={}",
                    appointmentNo, appointment.getScheduleId());
        }

        // 通知用户"预约已因超时被取消"。同样尽力而为。
        boolean notified;
        try {
            notified = notifier.notifyCancelled(toCancelledMessage(appointment));
        } catch (Exception e) {
            log.error("超时取消通知投递抛出异常（已兜住）: no={}", appointmentNo, e);
            notified = false;
        }
        if (!notified) {
            log.error("超时取消成功但通知投递失败（业务不受影响）: no={}", appointmentNo);
        }

        log.info("超时自动取消完成: no={} scheduleId={} 号源已归还={}",
                appointmentNo, appointment.getScheduleId(), returned == 1);
        return true;
    }

    // ==================================================================
    // 支付与就诊（模拟回调，决策 D-07）
    // ==================================================================

    /**
     * 推进订单状态：模拟支付回调与就诊完成。
     *
     * <h2>为什么需要这个方法（它不在任务书里，是补的）</h2>
     *
     * 任务书没列"支付"任务，但文档 D-07 写着"挂号的支付用**模拟回调**"。
     * 不做的话，状态机里 {@code PAID} 与 {@code COMPLETED} 就是**不可达的**——
     * 只有测试手动改状态才能到，而那只能证明"状态机枚举内部自洽"，
     * 证明不了"订单真的能沿着状态机走完"。
     *
     * <p>更实际的问题：演示时"15 分钟未支付自动取消"这条链路只讲了一半。
     * 另一半是**"已支付的订单不会被误取消"**——而如果到不了 {@code PAID}，
     * 这一半就**演示不出来**。所以这个入口是把那条链路补完整的必要件。
     *
     * <h2>为什么是一个通用方法而不是两个</h2>
     *
     * 支付（PENDING_PAYMENT → PAID）与就诊完成（PAID → COMPLETED）
     * 的**执行结构完全相同**：带起始状态的原子 UPDATE、
     * 受影响行数为 0 说明状态已被别人改过、只做通知不做补偿。
     * 写成两个方法会复制一遍这个结构，将来改一处忘一处。
     *
     * <h2>⚠️ 它绝不能是"任意状态直接改成任意状态"</h2>
     *
     * 合法性的唯一判据是状态机的 {@code AppointmentStatus#canTransitionTo}，包括：
     * <ul>
     *   <li>不能跳步（PENDING_PAYMENT 不能直接到 COMPLETED）</li>
     *   <li><b>终态不可变</b>（已取消/已完成的订单不能被回调"复活"）</li>
     *   <li>不可自环</li>
     * </ul>
     * 这三条都由 {@code AppointmentStatusMachineTest} 的穷举测试守着。
     *
     * <p>⚠️ 在真实系统里，这个入口必须校验<b>支付平台签名</b>，
     * 否则任何人构造一个请求就能把订单标记成已支付。本项目的模拟回调
     * 只在本地演示环境存在（见 docs/02 的 D-07 与 docs/01 的"不做清单"）。
     *
     * @return 迁移后的订单视图
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AppointmentView advanceStatus(Long userId, String appointmentNo,
                                         AppointmentStatus target, String note) {
        Appointment appointment = appointmentMapper.findByNoAndUser(appointmentNo, userId);
        if (appointment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "挂号单不存在：" + appointmentNo);
        }

        AppointmentStatus from = appointment.getStatus();

        // 幂等：已经是目标状态就直接返回（回调被重投是常态）
        if (from == target) {
            log.info("状态已是目标值，按幂等成功处理: no={} status={}", appointmentNo, target);
            return viewOf(userId, appointmentNo, true);
        }

        // 合法性由状态机裁决，这里不重复实现规则
        if (!from.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "不允许的状态变更：" + from + " → " + target);
        }

        int changed = appointmentMapper.transitionStatus(appointment.getId(), from, target, note);
        if (changed == 0) {
            // 在"读"与"改"之间状态变了（并发）。重新读一次判断目标是否已达成。
            Appointment latest = appointmentMapper.findByNoAndUser(appointmentNo, userId);
            if (latest != null && latest.getStatus() == target) {
                log.info("并发推进，按幂等成功处理: no={} status={}", appointmentNo, target);
                return viewOf(userId, appointmentNo, true);
            }
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "订单状态已变更，请刷新后重试");
        }

        // ⚠️ 已知取舍：这条状态变更**不发通知**。
        //    本项目只定义了 BOOKED / CANCELLED / REMINDER 三种通知类型，
        //    没有"已支付/已完成"。硬塞进 BOOKED 会让通知文案与实际发生的事对不上，
        //    而那比"少发一条通知"更糟——收到"预约成功"的人会以为重复预约了。
        log.info("订单状态已推进: no={} {} → {}", appointmentNo, from, target);

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
     * 投递挂号通知，<b>把 Notifier 可能抛出的任何异常都挡住</b>。
     *
     * <h2>为什么 Notifier 自己已经 try/catch 了，这里还要再挡一层</h2>
     *
     * 这是<b>第二道防线</b>，与号源的唯一索引、状态机的条件 UPDATE 是同一种思路：
     * 接口上写着"绝不抛异常"是一条<b>约定</b>，而约定靠人遵守，人会漏。
     * 框架代码（{@code RabbitTemplate}、连接池）抛的正是异常。
     *
     * <p>而这一层兜底的成本是几行代码，收益是
     * <b>A-07 从"取决于实现者记得 catch"变成"无论实现怎么写都成立"</b>。
     *
     * <p>⚠️ 这个判断不是空想的：T-011 的测试用了"总是抛异常"的桩，
     * 在只有第一道防线时<b>立刻把挂号打挂了</b>——说明这条兜底是必需的。
     *
     * @return 是否投递成功；<b>任何异常都返回 false，绝不向外抛</b>
     */
    private boolean tryNotifyBooked(Appointment appointment, Schedule schedule) {
        try {
            return notifier.notifyBooked(toBookedMessage(appointment, schedule));
        } catch (Exception e) {
            log.error("通知投递抛出异常（已兜住，业务不受影响）: no={}",
                    appointment.getAppointmentNo(), e);
            return false;
        }
    }

    /** 投递超时调度，同样兜住一切异常。理由见 {@link #tryNotifyBooked}。 */
    private boolean tryScheduleTimeout(String appointmentNo) {
        try {
            return notifier.schedulePaymentTimeout(appointmentNo);
        } catch (Exception e) {
            log.error("超时调度投递抛出异常（已兜住，业务不受影响）: no={}", appointmentNo, e);
            return false;
        }
    }
    /**
     * 组装"挂号成功"的通知消息。
     *
     * <p>把医生名与科室名一并放进消息（而不是只给订单号让消费者回查），
     * 是为了<b>解耦</b>：消费者不必知道订单表长什么样，拿到消息就能干活。
     * 理由详见 {@code NotificationMessage} 的注释。
     */
    private NotificationMessage toBookedMessage(Appointment a, Schedule s) {
        // 医生名/科室名从读模型取（它已经 JOIN 好了），而不是再加两个 Mapper 依赖
        AppointmentRow row = appointmentMapper.findRowByNoAndUser(a.getAppointmentNo(), a.getUserId());
        return new NotificationMessage(
                a.getAppointmentNo(), a.getUserId(),
                row == null ? null : row.doctorName(),
                row == null ? null : row.departmentName(),
                String.valueOf(a.getVisitDate()), a.getPeriod(),
                NotificationMessage.TYPE_BOOKED);
    }

    /** 组装"已取消"的通知消息。名字同样来自读模型。 */
    private NotificationMessage toCancelledMessage(Appointment a) {
        AppointmentRow row = appointmentMapper.findRowByNoAndUser(a.getAppointmentNo(), a.getUserId());
        return new NotificationMessage(
                a.getAppointmentNo(), a.getUserId(),
                row == null ? null : row.doctorName(),
                row == null ? null : row.departmentName(),
                String.valueOf(a.getVisitDate()), a.getPeriod(),
                NotificationMessage.TYPE_CANCELLED);
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
