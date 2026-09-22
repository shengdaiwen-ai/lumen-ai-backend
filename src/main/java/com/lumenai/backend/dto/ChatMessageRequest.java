package com.lumenai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 发送消息请求
 */
@Data
public class ChatMessageRequest {

    @NotBlank(message = "content 不能为空")
    @Size(max = 50000, message = "content 长度不能超过 50000")
    private String content;

    /** 模式: default / plan */
    private String mode = "default";

    /** 模型覆盖 */
    private String model;

    /** 附件列表 */
    private List<AttachmentDto> attachments;

    /** 自定义上下文参数 */
    private Map<String, Object> metadata;

    /**
     * 发起请求的设备标识（由 {@code ChatController} 从 {@code X-Device-Id} 头注入）。
     *
     * <p>用途：判断会话绑定的工作空间是否就在这台机器上 —— 不在就剔除本地类工具
     * （跨设备打开的会话只能聊天，见需求文档 §6.3）。</p>
     */
    private String deviceId;

    /**
     * 工作空间根目录下 `LUMEN.md` 的正文（客户端读取后上行）。
     *
     * <p>作为「项目约定」追加进系统提示词；<b>不入库、不进日志</b>，且显式声明不覆盖安全红线。</p>
     */
    private String projectConvention;
}
