package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 附件元数据（前端先上传拿 key，再在 chat 请求里带过来）
 */
@Data
public class AttachmentDto {

    /** 上传后后端返回的存储 key / 相对路径 */
    private String key;

    /** 原始文件名 */
    private String filename;

    /** MIME 类型，例如 image/png、text/plain */
    private String contentType;

    /** 文件大小（字节） */
    private Long size;

    /** 是否图片（方便 AgentEngine 判断） */
    private boolean image;
}
