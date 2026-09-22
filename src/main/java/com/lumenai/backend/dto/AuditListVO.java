package com.lumenai.backend.dto;

import com.lumenai.backend.entity.AuditLog;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 审计列表响应（GET /audit/list）：分页 + 过滤。 */
@Data
public class AuditListVO {

    private long total;

    private int page;

    private int size;

    private List<AuditLog> items;

    public static AuditListVO of(long total, int page, int size, List<AuditLog> items) {
        AuditListVO vo = new AuditListVO();
        vo.total = total;
        vo.page = page;
        vo.size = size;
        vo.items = items;
        return vo;
    }
}
