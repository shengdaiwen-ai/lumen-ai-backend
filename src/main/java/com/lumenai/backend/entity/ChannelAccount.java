package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 办公助理通道账号绑定（微信 iLink Bot）
 */
@Data
@TableName("channel_accounts")
public class ChannelAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** weixin / feishu */
    private String channel;

    /** iLink bot_id（即通道侧账号 ID） */
    private String accountId;

    /** 通道侧账号昵称 */
    private String accountName;

    /** unbound / binding / bound / offline */
    private String bindStatus;

    /** iLink bot_token（Bearer 认证用） */
    private String botToken;

    /** iLink 返回的 bot_id */
    private String ilinkBotId;

    /** iLink 返回的 user_id */
    private String ilinkUserId;

    /** iLink baseurl（登录后返回，后续 API 都走这个 baseUrl） */
    private String baseUrl;

    /** 最近一次二维码 token */
    private String qrCode;

    /** 二维码 URL（liteapp.weixin.qq.com/q/...） */
    private String qrImgContent;

    private LocalDateTime qrExpiresAt;

    private LocalDateTime lastPollAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
