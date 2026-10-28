package com.hrh.servicearrange.files;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 实例文件的本地存储。
 * <p>
 * 目录约定：{@code {root}/{instId}/{原始文件名}}，实例与目录一一对应，
 * 删除实例时按 {@code instId} 整目录级联删除，不会误删其它实例的文件。
 * <p>
 * 对外地址统一使用 {@code local://{instId}/{文件名}}，与历史上的 {@code filemgr://} 解耦：
 * 文件不再依赖外部文件服务，落盘位置可通过 {@code servea.file.root} 配置。
 */
@Component
public class LocalFileStorage {

    /**
     * 本地文件地址协议前缀
     */
    public static final String SCHEME = "local://";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileStorage.class);

    /**
     * 单文件大小上限（字节），默认 100MB
     */
    @Value("${servea.file.max-size:104857600}")
    private long maxSize;

    @Value("${servea.file.root:./data/files}")
    private String rootDir;

    /**
     * 存储文件。文件名重复时自动追加序号，保证同实例内不覆盖。
     *
     * @param instId           实例 id，同时作为存储子目录
     * @param originalFilename 原始文件名（含扩展名）
     * @param size             文件大小（字节），用于上限校验
     * @param in               文件流，由调用方负责关闭
     * @return {@code local://{instId}/{存储文件名}}
     */
    public String store(String instId, String originalFilename, long size, InputStream in) throws IOException {
        if (StrUtil.isBlank(instId)) {
            throw new IllegalArgumentException("实例 id 为空，无法保存文件");
        }
        if (size > maxSize) {
            throw new IllegalArgumentException("文件超过大小上限 " + maxSize + " 字节：" + originalFilename);
        }
        Path dir = instanceDir(instId);
        Files.createDirectories(dir);
        Path target = uniqueTarget(dir, safeName(originalFilename));
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.debug("file stored, instId={}, name={}, size={}", instId, target.getFileName(), size);
        return SCHEME + instId + "/" + target.getFileName();
    }

    /**
     * 解析本地文件地址，非 {@code local://} 或越界地址直接报错。
     */
    public Path resolve(String uri) {
        String relative = stripScheme(uri);
        Path root = root();
        Path target = root.resolve(relative).normalize();
        //防止 ../ 越出存储根目录
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件地址：" + uri);
        }
        return target;
    }

    /**
     * 仅当地址是本地协议时解析，其它协议（历史 {@code filemgr://}、http 等）返回 {@code null}。
     */
    public Path resolveIfSupported(String uri) {
        if (StrUtil.isBlank(uri) || !uri.startsWith(SCHEME)) {
            return null;
        }
        return resolve(uri);
    }

    public boolean exists(String uri) {
        Path path = resolveIfSupported(uri);
        return path != null && Files.isRegularFile(path);
    }

    /**
     * 删除实例目录下的全部文件。
     *
     * @return 实际删除的文件数（目录本身不计）
     */
    public int deleteByInstId(String instId) {
        if (StrUtil.isBlank(instId)) {
            return 0;
        }
        Path dir = instanceDir(instId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            deleted = (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            //统计失败不影响删除
            LOGGER.warn("count inst files fail, instId={}", instId, e);
        }
        try {
            FileUtil.del(dir.toFile());
        } catch (Exception e) {
            LOGGER.error("delete inst files fail, instId={}, dir={}", instId, dir, e);
            return 0;
        }
        return deleted;
    }

    private Path instanceDir(String instId) {
        Path root = root();
        Path dir = root.resolve(instId).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new IllegalArgumentException("非法的实例 id：" + instId);
        }
        return dir;
    }

    private Path root() {
        return Paths.get(rootDir).toAbsolutePath().normalize();
    }

    private String stripScheme(String uri) {
        if (StrUtil.isBlank(uri)) {
            throw new IllegalArgumentException("文件地址为空");
        }
        if (!uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("不支持的文件地址（仅支持 " + SCHEME + "）：" + uri);
        }
        String relative = uri.substring(SCHEME.length());
        if (StrUtil.isBlank(relative)) {
            throw new IllegalArgumentException("文件地址为空：" + uri);
        }
        return relative;
    }

    /**
     * 去掉路径片段与非法字符，只保留文件名本身。
     */
    private String safeName(String originalFilename) {
        String name = FileUtil.getName(originalFilename);
        if (StrUtil.isBlank(name)) {
            name = "file";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        if (name.length() > 120) {
            String ext = FileUtil.extName(name);
            String base = FileUtil.mainName(name);
            name = base.substring(0, 100) + (StrUtil.isEmpty(ext) ? "" : "." + ext);
        }
        return name;
    }

    private Path uniqueTarget(Path dir, String name) {
        Path target = dir.resolve(name);
        if (!Files.exists(target)) {
            return target;
        }
        String main = FileUtil.mainName(name);
        String ext = FileUtil.extName(name);
        for (int i = 1; i < 1000; i++) {
            target = dir.resolve(main + "-" + i + (StrUtil.isEmpty(ext) ? "" : "." + ext));
            if (!Files.exists(target)) {
                return target;
            }
        }
        throw new IllegalStateException("同名文件过多，无法生成存储文件名：" + name);
    }
}
