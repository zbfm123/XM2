package com.demo.hospital.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（手机号 + 口令）。
 *
 * <p>这里的长度限制比注册宽松（口令只需非空），原因：
 * 登录时去校验"口令是否符合强度规则"是错的——<b>规则变了就不该让老用户登不进来</b>。
 * 长度上限只用于挡住异常大的输入。
 */
public record LoginRequest(

        @NotBlank(message = "手机号不能为空")
        @Size(max = 20, message = "手机号过长")
        String phone,

        @NotBlank(message = "口令不能为空")
        @Size(max = 72, message = "口令过长")
        String password
) {
}
