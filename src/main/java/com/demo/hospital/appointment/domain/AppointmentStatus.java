package com.demo.hospital.appointment.domain;

import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 挂号订单状态机。
 *
 * <pre>
 * PENDING_PAYMENT ──支付──→ PAID ──就诊──→ COMPLETED
 *        │                   │
 *        │超时/主动取消        │主动取消
 *        ▼                   ▼
 *    CANCELLED           CANCELLED
 * </pre>
 *
 * <h2>为什么把迁移表写成"数据"而不是 if / switch</h2>
 *
 * 一种常见写法是在改状态的地方写 {@code if (status == PAID) { ... }}。
 * 那样有三个问题：
 * <ol>
 *   <li><b>合法迁移关系散落在各处</b>，想知道"PAID 能变成什么"要翻遍代码</li>
 *   <li><b>穷举测试无从下手</b>——没有一张可读的表，就没有可断言的对象</li>
 *   <li>每加一个状态，都要回头检查所有 if，<b>漏掉一处就是一条非法路径</b></li>
 * </ol>
 *
 * 这里把迁移关系集中成 {@link #TRANSITIONS} 一张不可变的表，
 * 于是"合法迁移"成了可以被测试穷举的数据（验收 A-08），
 * 而 {@link #canTransitionTo} 是唯一的判断入口。
 *
 * <p>这个结构与项目 1 的审查任务状态机同构，<b>面试时可以直接对照讲</b>：
 * 同一套"把状态迁移表当数据、用穷举测试锁住"的做法，用在了两个完全不同的业务上。
 */
public enum AppointmentStatus {

    /** 待支付。刚挂号成功时的初始状态。 */
    PENDING_PAYMENT,

    /** 已支付。 */
    PAID,

    /** 已完成就诊。<b>终态。</b> */
    COMPLETED,

    /** 已取消。<b>终态。</b> */
    CANCELLED;

    /**
     * 合法迁移表 —— <b>本类的核心，也是 A-08 穷举测试断言的对象</b>。
     *
     * <p>两条不变量（都由测试守住）：
     * <ul>
     *   <li><b>不可自环</b>：任何状态都不包含自身。否则"重复提交同一个状态变更"
     *       会被当成一次合法推进，而实际上什么都没发生——这种 bug 极难发现，
     *       因为接口返回成功、状态看起来也对</li>
     *   <li><b>终态无出边</b>：{@code COMPLETED} / {@code CANCELLED} 的集合为空。
     *       否则"已完成的挂号还能被取消"，而号源已经消耗掉了，数据就此不一致</li>
     * </ul>
     *
     * <p>⚠️ 用 {@link EnumMap} + {@link EnumSet} 而不是 {@code HashMap}：
     * 枚举做键时它们更快、更省内存，而且 {@link Collections#unmodifiableMap} 包一层之后
     * <b>这张表在运行期无法被改动</b>——状态机的定义不该有机会在运行时被谁改掉。
     */
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> TRANSITIONS;

    static {
        Map<AppointmentStatus, Set<AppointmentStatus>> m = new EnumMap<>(AppointmentStatus.class);
        m.put(PENDING_PAYMENT, Collections.unmodifiableSet(EnumSet.of(PAID, CANCELLED)));
        m.put(PAID, Collections.unmodifiableSet(EnumSet.of(COMPLETED, CANCELLED)));
        // 终态：显式写出空集合，而不是"省略不写"。
        // 省略会让"忘记定义"和"故意没有出边"看起来一样（都取不到值），
        // 而这两件事的含义完全不同——前者是 bug，后者是设计。
        m.put(COMPLETED, Collections.unmodifiableSet(EnumSet.noneOf(AppointmentStatus.class)));
        m.put(CANCELLED, Collections.unmodifiableSet(EnumSet.noneOf(AppointmentStatus.class)));
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    /**
     * 是否可以合法迁移到 {@code target}。
     *
     * <p>这是全项目<b>唯一</b>判断状态迁移合法性的地方。
     * 任何"能不能取消 / 能不能支付"的问题都必须问它，
     * 而不是在业务代码里各自比较枚举值。
     */
    public boolean canTransitionTo(AppointmentStatus target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 是否终态（无任何出边）。 */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }

    /** 本状态可以迁移到的所有状态（只读）。 */
    public Set<AppointmentStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    /**
     * 断言一次状态迁移合法，否则抛业务异常。
     *
     * <p>放在枚举里而不是各 Service 里，是为了让"非法迁移"的错误码与提示语只有一处定义：
     * <b>同一个非法操作，在任何入口都应当得到同样的错误码</b>，前端才能只写一套处理。
     *
     * <p>⚠️ 注意这里<b>只判断合法性，不修改任何状态</b>。
     * 真正的落库由调用方用带条件的原子 UPDATE 完成（见 T-007/T-008），
     * 因为"先判断再改"在并发下是有窗口的——本项目已经反复强调过这一点。
     */
    public void requireTransitionTo(AppointmentStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "不允许的状态变更：" + this + " → " + target);
        }
    }
}
