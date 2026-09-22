package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 登记工作空间元数据。
 *
 * <p>⚠️ <b>没有 path 字段</b>：客户端只上报名称，路径留在本机。服务端拿不到路径就没法泄露。</p>
 * <p>也没有权限档位：档位是账号级全局设置，登记空间时不需要、也不该携带。</p>
 */
@Data
public class WorkspaceCreateRequest {

    /** 展示名（默认取目录名）；同名同设备下唯一 */
    private String name;

    /**
     * 设备标识。
     * <p>优先取请求头 {@code X-Device-Id}，请求体给的值只在头缺失时兜底
     * （避免主进程直调场景拼不出头时整条链路不可用）。</p>
     */
    private String deviceId;
}
