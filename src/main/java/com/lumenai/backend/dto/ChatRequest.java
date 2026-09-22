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

    /**
     * 工作空间根目录下 `LUMEN.md` 的正文（客户端读取后上行，作为「项目约定」注入系统提示词）。
     * <p>限长 8KB，超出由服务端截断；只在内存里中转，不入库不进日志。</p>
     */
    private String projectConvention;
}
