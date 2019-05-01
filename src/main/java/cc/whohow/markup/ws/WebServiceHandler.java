package cc.whohow.markup.ws;

import cc.whohow.markup.Markdown;
import cc.whohow.markup.Markup;
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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@ChannelHandler.Sharable
public class WebServiceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LogManager.getLogger();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CharSequence TEXT_MARKDOWN = new AsciiString("text/markdown;charset=utf-8");
    private static final CharSequence CACHE_CONTROL_VALUE = new AsciiString("no-cache,max-age=86400,must-revalidate");
    private static final String SEARCH = "/.s";
    private static final String TABLE_OF_CONTENT = "/.toc";

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
        log.debug("{} {}", request.method(), request.uri());
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
            if (decoder.path() == null || decoder.path().equals("/")) {
                send(context, request, "/index.html");
            } else if (decoder.path().equals(TABLE_OF_CONTENT)) {
                toc(context);
            } else if (decoder.path().equals(SEARCH)) {
                search(context, decoder);
            } else {
                send(context, request, decoder.path());
            }
        } catch (NoSuchFileException | FileNotFoundException e) {
            log.error("NotFound", e);
            send(context, HttpResponseStatus.NOT_FOUND);
        } catch (Throwable e) {
            log.error("Error", e);
            send(context, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isBadRequest(FullHttpRequest request) {
        return !request.decoderResult().isSuccess();
    }

    private boolean isMethodNotAllowed(FullHttpRequest request) {
        return !HttpMethod.GET.equals(request.method());
    }

    private boolean isNotModified(FullHttpRequest request, long lastModified) {
        return request.headers().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, 0) / 1000 ==
                lastModified / 1000;
    }

    private String getFirst(QueryStringDecoder decoder, String key) {
        return getFirst(decoder, key, null);
    }

    private String getFirst(QueryStringDecoder decoder, String key, String defaultValue) {
        List<String> values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }

    private void send(ChannelHandlerContext context, HttpResponseStatus status) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        context.write(response);
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void send(ChannelHandlerContext context, FullHttpRequest request, String path) throws IOException {
        Path file = markup.resolve(path.substring(1));
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        if (isNotModified(request, lastModified)) {
            send(context, HttpResponseStatus.NOT_MODIFIED);
            return;
        }

        long contentLength = Files.size(file);
        CharSequence contentType = Files.probeContentType(file);
        if (contentType == null && path.endsWith(".md")) {
            contentType = TEXT_MARKDOWN;
        }
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.DATE, DateFormatter.format(new Date()));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        response.headers().set(HttpHeaderNames.LAST_MODIFIED, DateFormatter.format(new Date(lastModified)));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, CACHE_CONTROL_VALUE);
        context.write(response);
        context.write(new DefaultFileRegion(file.toFile(), 0, contentLength));
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void toc(ChannelHandlerContext context) throws Exception {
        Map<String, Object> result = Collections.singletonMap("toc", markup.list());
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(result);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.DATE, DateFormatter.format(new Date()));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        context.write(response);
        context.write(Unpooled.wrappedBuffer(bytes));
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void search(ChannelHandlerContext context, QueryStringDecoder decoder) throws Exception {
        SearchCursor searchCursor;
        String cursor = getFirst(decoder, "c");
        if (cursor == null || cursor.isEmpty()) {
            searchCursor = new SearchCursor();
            searchCursor.setPrefix(getFirst(decoder, "p"));
            searchCursor.setKeyword(getFirst(decoder, "q"));
            searchCursor.setCount(Integer.parseInt(getFirst(decoder, "n", "10")));
        } else {
            searchCursor = new SearchCursor(cursor);
        }

        SearchResult<Markdown> searchResult = markup.search(searchCursor);
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(searchResult);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.DATE, DateFormatter.format(new Date()));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        context.write(response);
        context.write(Unpooled.wrappedBuffer(bytes));
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
