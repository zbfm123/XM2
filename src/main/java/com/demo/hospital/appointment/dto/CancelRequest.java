package com.demo.hospital.appointment.dto;

import jakarta.validation.constraints.Size;

/**
 * 取消挂号请求。
 *
 * <p>{@code reason} 可选：不填就记一个中性的默认原因。
 * 强制填写会让"我不想要了"这种最常见的场景多一步摩擦，
 * 而取消原因在本项目里主要用于展示与排查，不是审计要求。
 */
public record CancelRequest(

        @Size(max = 255, message = "取消原因过长")
        String reason
) {
}
