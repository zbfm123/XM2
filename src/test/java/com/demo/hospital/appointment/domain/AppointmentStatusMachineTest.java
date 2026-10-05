package com.demo.hospital.appointment.domain;

import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 挂号状态机的穷举测试 —— 验收 <b>A-08</b>。
 *
 * <h2>这份测试的关键设计：预期表是"独立写一遍"的，不是从枚举里读出来的</h2>
 *
 * 最容易写错的一种穷举测试是这样：
 * <pre>
 * for (var from : values())
 *     for (var to : values())
 *         assertThat(from.canTransitionTo(to)).isEqualTo(from.allowedTargets().contains(to));
 * </pre>
 * 它看起来很"完整"，实际上<b>是同义反复</b>——等式两边都来自同一个实现。
 * 实现里写错了，这条断言照样通过，测试给出的是虚假的安全感。
 *
 * <p>所以这里的 {@link #EXPECTED} 是<b>照着 docs/02-architecture.md 的状态机图手写的第二份定义</b>。
 * 两处独立描述必须一致，测试才有意义。这也正是"穷举"的价值所在：
 * <b>把所有组合都摆出来，让任何一处不一致都无处可藏。</b>
 *
 * <p>四个状态 × 四个状态 = 16 个组合，全部逐一断言，一条不漏。
 */
class AppointmentStatusMachineTest {

    /**
     * 预期迁移表 —— <b>独立于实现手写的第二份定义</b>。
     *
     * <p>来源：docs/02-architecture.md 第六节的状态机图。
     *
     * <pre>
     * PENDING_PAYMENT ──→ PAID ──→ COMPLETED
     *        │             │
     *        └─────────────┴──→ CANCELLED
     * </pre>
     *
     * <p>⚠️ 修改状态机时，<b>必须同时改这里和 {@code AppointmentStatus.TRANSITIONS}</b>。
     * 只改一处会让测试失败——这正是我们想要的：改动必须是有意的，不能是顺手漏掉的。
     */
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> EXPECTED = new EnumMap<>(AppointmentStatus.class);

    static {
        EXPECTED.put(AppointmentStatus.PENDING_PAYMENT,
                EnumSet.of(AppointmentStatus.PAID, AppointmentStatus.CANCELLED));
        EXPECTED.put(AppointmentStatus.PAID,
                EnumSet.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED));
        EXPECTED.put(AppointmentStatus.COMPLETED, EnumSet.noneOf(AppointmentStatus.class));
        EXPECTED.put(AppointmentStatus.CANCELLED, EnumSet.noneOf(AppointmentStatus.class));
    }

    // ------------------------------------------------------------------
    // 1. 穷举全部 16 个状态对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-08 穷举全部 16 个状态对，合法性必须与预期表逐条一致")
    void allStatePairsMustMatchExpectedTable() {
        int checked = 0;

        for (AppointmentStatus from : AppointmentStatus.values()) {
            for (AppointmentStatus to : AppointmentStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s 的合法性", from, to)
                        .isEqualTo(expected);
                checked++;
            }
        }

        // 断言"确实穷举了"，而不是循环没跑或提前退出却显示通过
        int states = AppointmentStatus.values().length;
        assertThat(checked).as("穷举的组合数应为 n×n").isEqualTo(states * states);
        assertThat(checked).isEqualTo(16);
    }

    @Test
    @DisplayName("A-08 预期表本身必须覆盖每一个状态（防止新增状态后测试静默漏测）")
    void expectedTableMustCoverEveryStatus() {
        // 这条守的是"实现加了新状态、但预期表忘了加"的情况。
        // 若 EXPECTED 用 getOrDefault 之类的写法，新状态会被当成"没有出边"而悄悄通过。
        assertThat(EXPECTED.keySet())
                .as("预期表必须覆盖枚举里的每一个状态")
                .containsExactlyInAnyOrder(AppointmentStatus.values());
    }

    // ------------------------------------------------------------------
    // 2. 两条不变量：不可自环、终态无出边
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("A-08 不变量一：任何状态都不可自环")
    void noStateShouldTransitionToItself(AppointmentStatus status) {
        assertThat(status.canTransitionTo(status))
                .as("%s 不能迁移到自身——否则\"重复提交\"会被当成一次合法推进", status)
                .isFalse();
    }

    @Test
    @DisplayName("A-08 不变量二：终态无任何出边")
    void terminalStatesMustHaveNoOutgoingEdges() {
        assertThat(AppointmentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(AppointmentStatus.CANCELLED.isTerminal()).isTrue();

        // 逐条检查"终态不能到任何地方"，而不只是检查 isTerminal() 这个标志位：
        // 标志位和实际迁移表有可能不一致，穷举才是真的。
        for (AppointmentStatus terminal : Set.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED)) {
            for (AppointmentStatus to : AppointmentStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("终态 %s 不应能迁移到 %s", terminal, to)
                        .isFalse();
            }
            assertThat(terminal.allowedTargets()).as("%s 的出边集合应为空", terminal).isEmpty();
        }
    }

    @Test
    @DisplayName("非终态必须有出边——不存在\"走不出去\"的状态")
    void nonTerminalStatesMustHaveOutgoingEdges() {
        for (AppointmentStatus s : AppointmentStatus.values()) {
            if (s.isTerminal()) {
                continue;
            }
            assertThat(s.allowedTargets())
                    .as("%s 不是终态，必须至少有一条出边，否则订单会永久卡在这个状态", s)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("只有 PENDING_PAYMENT 与 PAID 不是终态")
    void terminalSetIsExactlyAsExpected() {
        Set<AppointmentStatus> terminal = EnumSet.noneOf(AppointmentStatus.class);
        for (AppointmentStatus s : AppointmentStatus.values()) {
            if (s.isTerminal()) {
                terminal.add(s);
            }
        }
        assertThat(terminal).containsExactlyInAnyOrder(
                AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED);
    }

    // ------------------------------------------------------------------
    // 3. 迁移表的具体形状（业务语义层面的断言）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法迁移恰好 4 条：PENDING→PAID/CANCELLED、PAID→COMPLETED/CANCELLED")
    void legalTransitionCountShouldBeFour() {
        int legal = 0;
        Set<String> pairs = new HashSet<>();

        for (AppointmentStatus from : AppointmentStatus.values()) {
            for (AppointmentStatus to : AppointmentStatus.values()) {
                if (from.canTransitionTo(to)) {
                    legal++;
                    pairs.add(from + "→" + to);
                }
            }
        }

        assertThat(pairs).containsExactlyInAnyOrder(
                "PENDING_PAYMENT→PAID",
                "PENDING_PAYMENT→CANCELLED",
                "PAID→COMPLETED",
                "PAID→CANCELLED");
        // 16 个组合里只有 4 个合法 —— 非法路径占绝大多数，这正是必须穷举的理由
        assertThat(legal).isEqualTo(4);
    }

    @Test
    @DisplayName("已完成的挂号不可取消（F-03-8）——这是终态无出边的业务含义")
    void completedAppointmentCannotBeCancelled() {
        assertThat(AppointmentStatus.COMPLETED.canTransitionTo(AppointmentStatus.CANCELLED)).isFalse();
        // 已完成的挂号也不能回退到已支付
        assertThat(AppointmentStatus.COMPLETED.canTransitionTo(AppointmentStatus.PAID)).isFalse();
        assertThat(AppointmentStatus.COMPLETED.canTransitionTo(AppointmentStatus.PENDING_PAYMENT)).isFalse();
    }

    @Test
    @DisplayName("已取消的挂号不能\"复活\"")
    void cancelledAppointmentCannotBeRevived() {
        for (AppointmentStatus to : AppointmentStatus.values()) {
            assertThat(AppointmentStatus.CANCELLED.canTransitionTo(to)).isFalse();
        }
    }

    @Test
    @DisplayName("不能跳过支付直接完成（PENDING_PAYMENT → COMPLETED 非法）")
    void cannotSkipPayment() {
        assertThat(AppointmentStatus.PENDING_PAYMENT.canTransitionTo(AppointmentStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("已支付的挂号不能再支付一次（PAID → PAID 非法，由不可自环保证）")
    void cannotPayTwice() {
        assertThat(AppointmentStatus.PAID.canTransitionTo(AppointmentStatus.PAID)).isFalse();
    }

    // ------------------------------------------------------------------
    // 4. requireTransitionTo：非法迁移抛异常且错误码可识别
    // ------------------------------------------------------------------

    @Test
    @DisplayName("requireTransitionTo 对非法迁移抛 INVALID_STATE，提示语里带上两个状态")
    void requireTransitionShouldThrowOnIllegalTransition() {
        assertThatThrownBy(() ->
                AppointmentStatus.COMPLETED.requireTransitionTo(AppointmentStatus.CANCELLED))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("CANCELLED");
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class,
            names = {"PENDING_PAYMENT", "PAID"})
    @DisplayName("requireTransitionTo 对合法迁移不抛异常")
    void requireTransitionShouldPassForLegalTransitions(AppointmentStatus from) {
        for (AppointmentStatus to : from.allowedTargets()) {
            from.requireTransitionTo(to);   // 不抛异常即通过
        }
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    @DisplayName("null 目标状态一律非法，且不抛 NPE")
    void nullTargetShouldBeRejectedNotThrow(AppointmentStatus status) {
        assertThat(status.canTransitionTo(null)).isFalse();
        assertThatThrownBy(() -> status.requireTransitionTo(null))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 5. 迁移表不可被运行期改动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("allowedTargets 返回的是只读集合——状态机定义不该在运行期被改掉")
    void allowedTargetsShouldBeUnmodifiable() {
        Set<AppointmentStatus> targets = AppointmentStatus.PENDING_PAYMENT.allowedTargets();

        assertThatThrownBy(() -> targets.add(AppointmentStatus.COMPLETED))
                .isInstanceOf(UnsupportedOperationException.class);

        // 确认没被改动
        assertThat(AppointmentStatus.PENDING_PAYMENT.allowedTargets())
                .containsExactlyInAnyOrder(AppointmentStatus.PAID, AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("状态名的字符长度不超过 DB 列宽 VARCHAR(32)——否则落库时会被截断或报错")
    void statusNamesMustFitDatabaseColumn() {
        // appointment.status 是 VARCHAR(32)（见 db/schema.sql）。
        // 枚举名一旦变长就越界，而这类问题往往到 T-007 真正写库时才暴露。
        for (AppointmentStatus s : AppointmentStatus.values()) {
            assertThat(s.name().length())
                    .as("%s 的名字长度必须 ≤ 32", s)
                    .isLessThanOrEqualTo(32);
        }
    }
}
