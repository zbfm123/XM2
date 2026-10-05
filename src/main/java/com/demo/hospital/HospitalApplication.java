package com.demo.hospital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 医院预约挂号系统。
 *
 * <p>与项目 1（合同智能审查平台）的定位区分：
 * <ul>
 *   <li>项目 1：展示<b>工程判断力</b>（AI 工程化、证据对齐、只追加审计）</li>
 *   <li>本项目：展示<b>主流企业开发能力</b>（前后端分离、异步消息、并发控制、部署）</li>
 * </ul>
 *
 * <p>核心业务亮点是<b>防止号源超卖</b>，见 {@code schedule} 包的实现。
 */
@SpringBootApplication
public class HospitalApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalApplication.class, args);
    }
}
