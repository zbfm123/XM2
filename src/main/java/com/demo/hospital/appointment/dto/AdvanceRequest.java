package com.demo.hospital.appointment.dto;

import jakarta.validation.constraints.Size;

/**
 * 模拟状态推进请求（决策 D-07：不接真实支付）。
 *
 * <p>⚠️ 这个请求体里**没有"目标状态"字段**——目标状态由 URL 路径决定
 * （见 {@code AppointmentController#pay} 与 {@code #complete}）。
 *
 * <p>为什么不让客户端传 {@code target}：那等于把"这个订单能变成什么"
 * 交给调用方决定，而唯一该有发言权的是<b>状态机</b>。
 * 路径里写死目标，客户端就只能请求两个明确命名的动作（支付/完成），
 * 想让它变成别的状态也没有入口。
 *
 * @param note 备注（可空）。会写进 {@code cancel_reason} 那一列复用作为"变更说明"。
 */
public record AdvanceRequest(

        @Size(max = 255, message = "备注过长")
        String note
) {
}
