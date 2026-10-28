package com.hrh.servicearrange.service;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.files.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 实例文件的读写入口：上传落盘、输出下载、删除实例时清理目录。
 * <p>
 * 控制器只负责解析请求与组装响应，文件系统细节全部收口在这里，
 * 便于单独替换存储实现（本地目录 / 对象存储）。
 *
 * @author huangrenhui
 */
@Slf4j
@Service
public class InstFileService {

    @Autowired
    private LocalFileStorage fileStorage;

    /**
     * 把 multipart 上传的文件写入本地实例目录，并把参数值替换为 {@code local://} 地址。
     * <p>
     * 同名文件自动追加序号；任何一个文件保存失败都会抛出异常，避免实例带着 `"path"` 占位值继续运行。
     *
     * @param request  原始 multipart 请求
     * @param instId   实例 id，同时作为存储子目录
     * @param paramObj 待回填的参数对象
     */
    public void storeUploadFiles(StandardMultipartHttpServletRequest request, String instId, JSONObject paramObj) {
        MultiValueMap<String, MultipartFile> multiFiles = request.getMultiFileMap();
        for (Map.Entry<String, List<MultipartFile>> entry : multiFiles.entrySet()) {
            MultipartFile mFile = entry.getValue() == null ? null : entry.getValue().get(0);
            if (mFile == null || mFile.isEmpty()) {
                continue;
            }
            try (InputStream in = mFile.getInputStream()) {
                String uri = fileStorage.store(instId, mFile.getOriginalFilename(), mFile.getSize(), in);
                paramObj.set(entry.getKey(), uri);
                log.debug("upload file stored, key={}, name={}, size={}, uri={}",
                        entry.getKey(), mFile.getOriginalFilename(), mFile.getSize(), uri);
            } catch (Exception e) {
                throw new IllegalStateException("上传文件保存失败：" + mFile.getOriginalFilename() + "，" + e.getMessage(), e);
            }
        }
    }

    /**
     * 按 {@code local://} 地址把实例输出文件写回响应流。
     *
     * @param outputs  实例输出，{@code value} 为文件地址
     * @param response HTTP 响应
     */
    public void writeDownload(Task.Result outputs, HttpServletResponse response) throws Exception {
        String fileUri = outputs.getValue();
        if (StrUtil.isBlank(fileUri)) {
            throw new IllegalArgumentException("输出为文件类型，但 outputs.value 为空，无法下载");
        }
        Path localFile = fileStorage.resolve(fileUri);
        if (!Files.isRegularFile(localFile)) {
            throw new IllegalArgumentException("输出文件不存在或已被清理：" + fileUri);
        }
        String fileName = localFile.getFileName().toString();
        String encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        String contentType = outputs.getContentType();
        if (StrUtil.isBlank(contentType)) {
            contentType = Files.probeContentType(localFile);
        }
        response.setContentType(StrUtil.isBlank(contentType) ? "application/octet-stream" : contentType);
        response.setContentLengthLong(Files.size(localFile));
        try (InputStream in = Files.newInputStream(localFile)) {
            IoUtil.copy(in, response.getOutputStream());
        }
        response.flushBuffer();
    }

    /**
     * 删除实例目录下的全部文件。
     *
     * @param instId 实例 id
     * @return 实际删除的文件数
     */
    public int deleteByInstId(String instId) {
        return fileStorage.deleteByInstId(instId);
    }
}
