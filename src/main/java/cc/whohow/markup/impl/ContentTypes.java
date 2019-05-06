package cc.whohow.markup.impl;

import cc.whohow.markup.ws.Server;

import javax.activation.MimetypesFileTypeMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MIME Content-Type
 */
public class ContentTypes {
    private static final Map<String, String> CONTENT_TYPES_WITH_CHARSET = new ConcurrentHashMap<>();

    static {
        try (InputStream stream = Server.class.getResourceAsStream("/mime.types")) {
            MimetypesFileTypeMap.setDefaultFileTypeMap(new MimetypesFileTypeMap(stream));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CONTENT_TYPES_WITH_CHARSET.put("application/xml", "application/xml;charset=utf-8");
        CONTENT_TYPES_WITH_CHARSET.put("application/json", "application/json;charset=utf-8");
        CONTENT_TYPES_WITH_CHARSET.put("application/javascript", "application/javascript;charset=utf-8");
    }

    /**
     * 探测文件类型
     */
    public static String probeContentType(Path path) throws IOException {
        String probe1 = MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(path.toFile());
        if (probe1 != null) {
            return getContentTypeWithCharset(probe1);
        }
        String probe2 = Files.probeContentType(path);
        if (probe2 != null) {
            return getContentTypeWithCharset(probe2);
        }
        return null;
    }

    /**
     * 根据名称探测文件类型
     */
    public static String probeContentType(String name) {
        return getContentTypeWithCharset(MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(name));
    }

    /**
     * 添加字符集，默认utf-8
     */
    private static String getContentTypeWithCharset(String contentType) {
        return CONTENT_TYPES_WITH_CHARSET.computeIfAbsent(contentType, (key) -> {
            if (key.startsWith("text/")) {
                return key + ";charset=utf-8";
            }
            return key;
        });
    }
}
