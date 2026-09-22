package com.lumenai.backend.dto;

import java.util.List;

/**
 * 工作空间列表 + 配额用量（一次请求拿全，避免前端串行两次调用）。
 *
 * <p>{@code limit} 为 {@code -1} 表示不限。前端据此决定「新建」是否置灰，
 * <b>但不自行推算档位</b> —— 档位判定全在服务端（前端规范红线 14 的同源约束）。</p>
 */
public class WorkspaceListVO {

    private List<WorkspaceVO> items;
    private int used;
    private int limit;

    public WorkspaceListVO(List<WorkspaceVO> items, int used, int limit) {
        this.items = items;
        this.used = used;
        this.limit = limit;
    }

    public List<WorkspaceVO> getItems() { return items; }
    public void setItems(List<WorkspaceVO> items) { this.items = items; }

    public int getUsed() { return used; }
    public void setUsed(int used) { this.used = used; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
