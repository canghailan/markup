package cc.whohow.markup.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * 打包静态文件
 */
public class ClasspathStatic {
    /**
     * 最后修改时间（启动时间）
     */
    private Date lastModified;
    /**
     * 内容缓存
     */
    private Cache<String, byte[]> content;
    /**
     * 元数据缓存
     */
    private Cache<String, Metadata> metadata;

    public ClasspathStatic() {
        lastModified = new Date();
        content = CacheBuilder.newBuilder()
                .build();
        metadata = CacheBuilder.newBuilder()
                .build();
    }

    public byte[] read(String key) throws Exception {
        return content.get(key, () -> readContent(key));
    }

    public Metadata getMetadata(String key) throws Exception {
        return metadata.get(key, () -> readMetadata(key));
    }

    private Metadata readMetadata(String key) throws Exception {
        URL url = getClass().getResource(getClasspath(key));
        if (url == null) {
            return Metadata.NOT_FOUND;
        }
        return new Metadata(read(key).length, lastModified, ContentTypes.probeContentType(key));
    }

    private byte[] readContent(String key) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(getClasspath(key))) {
            return ByteStreams.toByteArray(stream);
        }
    }

    private String getClasspath(String key) {
        return "/static/" + key;
    }
}
