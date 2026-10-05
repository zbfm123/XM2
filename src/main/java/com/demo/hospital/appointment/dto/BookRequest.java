package com.demo.hospital.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交挂号请求。
 *
 * <p>⚠️ {@code idempotencyKey} 是<b>必填</b>，这一点是刻意的：
 * 如果把它做成可选（不传就不去重），那么"防重复提交"就变成了一个
 * <b>依赖客户端自觉</b>的功能——而客户端恰恰是最不可控的一环
 * （用户双击、网络重试、前端重试逻辑写错，都发生在这里）。
 *
 * <p>强制要求客户端提供，等于把"这次提交是同一个意图"这件事<b>显式化</b>：
 * 客户端必须自己回答"我怎么知道这两次是同一件事"。
 * 常见的做法是进入挂号页时生成一个 UUID，直到这次提交有了确定结果才更换。
 *
 * <p>长度限定 128 与数据库列宽一致（{@code idempotency_key VARCHAR(128)}）。
 * 不在这里限制的话，超长值会在写库时被截断或报错——
 * <b>截断尤其危险：两个不同的键被截成同一个，会被误判为重复提交。</b>
 */
public record BookRequest(

        @NotNull(message = "排班 id 不能为空")
        Long scheduleId,

        @NotBlank(message = "幂等键不能为空（客户端需为每次提交生成唯一键）")
        @Size(max = 128, message = "幂等键过长")
        String idempotencyKey
) {
}
