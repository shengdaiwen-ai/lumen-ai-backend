package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.AttachmentDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 文件上传接口 — 供聊天附件调用 */
@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

    @Value("${lumen.upload.dir:${user.home}/.lumen-ai/uploads}")
    private String uploadDir;

    /** 单文件上传 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<AttachmentDto> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return R.fail(400, "文件不能为空");
        }

        try {
            // 确保上传目录存在
            Path base = Paths.get(uploadDir, String.valueOf(UserContext.get()));
            Files.createDirectories(base);

            // 生成 key：{uuid}_{原始文件名}
            String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            String safeName = original.replaceAll("[\\\\/:*?\"<>|]", "_");
            String key = UUID.randomUUID() + "_" + safeName;

            File dest = base.resolve(key).toFile();
            file.transferTo(dest);

            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            boolean isImage = contentType.startsWith("image/");

            log.info("[File] upload userId={}, key={}, size={}, mime={}",
                    UserContext.get(), key, file.getSize(), contentType);

            AttachmentDto dto = new AttachmentDto();
            dto.setKey(key);
            dto.setFilename(original);
            dto.setContentType(contentType);
            dto.setSize(file.getSize());
            dto.setImage(isImage);
            return R.ok(dto);
        } catch (IOException e) {
            log.error("[File] upload failed", e);
            return R.fail(500, "文件保存失败: " + e.getMessage());
        }
    }

    /** 批量上传 */
    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<List<AttachmentDto>> uploadBatch(@RequestParam("files") MultipartFile[] files) {
        // 简单实现：逐个调 upload，实际可以并行
        return R.ok(java.util.Arrays.stream(files)
                .map(f -> upload(f).getData())
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    /** 根据 key 访问文件（图片预览 / 文件下载） */
    @GetMapping("/{key}")
    public org.springframework.http.ResponseEntity<byte[]> getFile(@PathVariable String key) {
        try {
            Path path = resolveKey(key);
            if (!Files.exists(path)) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(path);
            // 用文件名推断 content-type
            String contentType = Files.probeContentType(path);
            if (contentType == null) contentType = "application/octet-stream";
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("Content-Disposition", "inline; filename=\"" + key.substring(key.indexOf("_") + 1) + "\"")
                    .body(data);
        } catch (IOException e) {
            log.error("[File] get file failed, key={}", key, e);
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    /** 内部工具：根据 key 解析磁盘路径 */
    public Path resolveKey(String key) {
        return Paths.get(uploadDir, String.valueOf(UserContext.get()), key);
    }
}
