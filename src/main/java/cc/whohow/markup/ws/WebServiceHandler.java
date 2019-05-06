package cc.whohow.markup.ws;

import cc.whohow.markup.Markdown;
import cc.whohow.markup.Markup;
import cc.whohow.markup.MarkupGitRepository;
import cc.whohow.markup.impl.ClasspathStatic;
import cc.whohow.markup.impl.Metadata;
import cc.whohow.markup.impl.SearchCursor;
import cc.whohow.markup.impl.SearchResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.*;
import io.netty.util.AsciiString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;

/**
 * Markup Web 服务
 */
@ChannelHandler.Sharable
public class WebServiceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LogManager.getLogger("ws");
    private static final String SEARCH = "/.s";
    private static final String TABLE_OF_CONTENT = "/.toc";
    private static final String UPDATE = "/.updater";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CharSequence APPLICATION_JSON = new AsciiString("application/json;charset=utf-8");
    private static final CharSequence DEFAULT_CACHE_CONTROL_VALUE = new AsciiString("no-cache,max-age=86400,must-revalidate");
    private static final ClasspathStatic STATIC = new ClasspathStatic();

    static {
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final Markup markup;

    public WebServiceHandler(Markup markup) {
        this.markup = markup;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        log.info("{} {}", request.method(), request.uri());
        if (isBadRequest(request)) {
            send(context, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        if (isMethodNotAllowed(request)) {
            send(context, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        try {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String path = decoder.path();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }
            if (path.startsWith("/.")) {
                switch (path) {
                    case TABLE_OF_CONTENT: {
                        toc(context);
                        return;
                    }
                    case SEARCH: {
                        search(context, decoder.parameters());
                        return;
                    }
                    case UPDATE: {
                        update(context);
                        return;
                    }
                    default: {
                        send(context, HttpResponseStatus.NOT_FOUND);
                        return;
                    }
                }
            }
            if (HttpMethod.GET.equals(request.method())) {
                send(context, request, path);
            } else {
                send(context, HttpResponseStatus.METHOD_NOT_ALLOWED);
            }
        } catch (NoSuchFileException | FileNotFoundException e) {
            log.error("NotFound", e);
            send(context, HttpResponseStatus.NOT_FOUND);
        } catch (Throwable e) {
            log.error("Error", e);
            send(context, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 目录
     */
    private void toc(ChannelHandlerContext context) throws Exception {
        Map<String, Object> result = Collections.singletonMap("toc", markup.list());
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(result);

        send(context, Unpooled.wrappedBuffer(bytes),
                HttpHeaderNames.DATE, DateFormatter.format(new Date()),
                HttpHeaderNames.CONTENT_LENGTH, bytes.length,
                HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON,
                HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
    }

    /**
     * 搜索
     */
    private void search(ChannelHandlerContext context, Map<String, List<String>> parameters) throws Exception {
        SearchCursor searchCursor;
        String cursor = getFirst(parameters, "c").orElse(null);
        if (cursor == null || cursor.isEmpty()) {
            searchCursor = new SearchCursor();
            searchCursor.setPrefix(getFirst(parameters, "p").orElse(null));
            searchCursor.setKeyword(getFirst(parameters, "q").orElse(null));
            searchCursor.setCount(getFirst(parameters, "n").map(Integer::parseInt).orElse(10));
        } else {
            searchCursor = new SearchCursor(cursor);
        }

        SearchResult<Markdown> searchResult = markup.search(searchCursor);
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(searchResult);

        send(context, Unpooled.wrappedBuffer(bytes),
                HttpHeaderNames.DATE, DateFormatter.format(new Date()),
                HttpHeaderNames.CONTENT_LENGTH, bytes.length,
                HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON,
                HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
    }

    /**
     * 更新
     */
    private void update(ChannelHandlerContext context) throws IOException {
        Map<String, Object> result = new HashMap<>();
        try {
            markup.update();
            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false);
            log.error("update", e);
        }
        result.put("timestamp", new Date());
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(result);

        send(context, Unpooled.wrappedBuffer(bytes),
                HttpHeaderNames.DATE, DateFormatter.format(new Date()),
                HttpHeaderNames.CONTENT_LENGTH, bytes.length,
                HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON,
                HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
    }

    /**
     * 静态文件
     */
    private void send(ChannelHandlerContext context, FullHttpRequest request, String path) throws Exception {
        String key = path.substring(1);

        MarkupGitRepository gitRepository = markup.getGitRepository();
        Metadata metadata = gitRepository.getMetadata(key);
        if (metadata == Metadata.NOT_FOUND) {
            metadata = STATIC.getMetadata(key);
            if (metadata == Metadata.NOT_FOUND) {
                send(context, HttpResponseStatus.NOT_FOUND);
                return;
            } else if (isNotModified(request, metadata.getLastModified().getTime())) {
                send(context, HttpResponseStatus.NOT_MODIFIED);
                return;
            }
            byte[] bytes = STATIC.read(key);
            send(context, Unpooled.wrappedBuffer(bytes),
                    HttpHeaderNames.DATE, DateFormatter.format(new Date()),
                    HttpHeaderNames.CONTENT_LENGTH, metadata.getSize(),
                    HttpHeaderNames.CONTENT_TYPE, metadata.getContentType(),
                    HttpHeaderNames.LAST_MODIFIED, DateFormatter.format(metadata.getLastModified()),
                    HttpHeaderNames.CACHE_CONTROL, DEFAULT_CACHE_CONTROL_VALUE);
            return;
        } else if (isNotModified(request, metadata.getLastModified().getTime())) {
            send(context, HttpResponseStatus.NOT_MODIFIED);
            return;
        }

        send(context, new DefaultFileRegion(gitRepository.resolve(key).toFile(), 0, metadata.getSize()),
                HttpHeaderNames.DATE, DateFormatter.format(new Date()),
                HttpHeaderNames.CONTENT_LENGTH, metadata.getSize(),
                HttpHeaderNames.CONTENT_TYPE, metadata.getContentType(),
                HttpHeaderNames.LAST_MODIFIED, DateFormatter.format(metadata.getLastModified()),
                HttpHeaderNames.CACHE_CONTROL, DEFAULT_CACHE_CONTROL_VALUE);
    }

    private void send(ChannelHandlerContext context, HttpResponseStatus status) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        context.write(response);
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void send(ChannelHandlerContext context, Object body, Object... headers) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i + 1] != null) {
                response.headers().set((CharSequence) headers[i], headers[i + 1]);
            }
        }
        context.write(response);
        context.write(body);
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private boolean isBadRequest(FullHttpRequest request) {
        return !request.decoderResult().isSuccess();
    }

    private boolean isMethodNotAllowed(FullHttpRequest request) {
        return !HttpMethod.GET.equals(request.method()) && !HttpMethod.POST.equals(request.method());
    }

    private boolean isNotModified(FullHttpRequest request, long lastModified) {
        return request.headers().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, 0) / 1000 ==
                lastModified / 1000;
    }

    private Optional<String> getFirst(Map<String, List<String>> parameters, String key) {
        List<String> values = parameters.get(key);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0));
    }
}
