package com.lumenai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    @NotBlank(message = "消息不能为空")
    private String content;

    private Long sessionId;

    private String mode;

    /** 自动化任务执行记录ID（可选），聊天完成时用于更新执行历史状态 */
    private Long scheduleRunId;

    /** 附件列表 */
    private List<AttachmentDto> attachments;
}
