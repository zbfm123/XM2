package com.demo.hospital.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（手机号 + 口令）。
 *
 * <p>为什么手机号用正则而不是只校验长度：手机号是<b>登录账号</b>。
 * 放一个格式随意的值进来，等于制造一个以后再也没人能登录的账号
 * （用户记不住自己当时输入了什么）。校验放在入口，成本最低。
 *
 * <p>{@code realName} 允许为空：挂号场景下"姓名"是演示数据的一部分，
 * 强迫用户在注册时填写只会增加演示时的摩擦。为空时由服务端给一个中性的默认值。
 *
 * <p>{@code password} 上限定 72 字节不是形式主义：BCrypt 只取前 72 字节，
 * 超长输入既无意义又白烧 CPU（也是一个轻量的拒绝服务面）。
 */
public record RegisterRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "口令不能为空")
        @Size(min = 8, max = 72, message = "口令长度需在 8~72 之间")
        String password,

        @Size(max = 50, message = "姓名过长")
        String realName
) {
}
