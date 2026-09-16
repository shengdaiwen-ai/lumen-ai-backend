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

    /** 工作目录 */
    private String projectPath;

    /** 模式: default / plan */
    private String mode = "default";

    /** 模型覆盖 */
    private String model;

    /** 附件列表 */
    private List<AttachmentDto> attachments;

    /** 自定义上下文参数 */
    private Map<String, Object> metadata;
}
