package com.lumenai.backend.dto;

/**
 * 配额用量（设置页单独查询用）。
 *
 * @param used  已用空间数
 * @param limit 档位上限；{@code -1} = 不限
 */
public record WorkspaceUsageVO(int used, int limit) {
}
