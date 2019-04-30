package cc.whohow.markup.ws;

import cc.whohow.markup.service.MarkdownManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.*;

import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Date;

public class WebServiceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static MarkdownManager markdownManager = new MarkdownManager(".");

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        if (isBadRequest(request)) {
            sendStatus(context, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        if (isMethodNotAllowed(request)) {
            sendStatus(context, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        URI uri = URI.create(request.uri());
        try {
            Path path = markdownManager.resolve(uri.getPath());
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            if (isNotModified(request, lastModified)) {
                sendStatus(context, HttpResponseStatus.NOT_MODIFIED);
                return;
            }

            long fileLength = Files.size(path);
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.DATE, DateFormatter.format(new Date()));
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, Files.probeContentType(path));
            response.headers().set(HttpHeaderNames.LAST_MODIFIED, DateFormatter.format(new Date(lastModified)));
            response.headers().set(HttpHeaderNames.CACHE_CONTROL,  "no-cache,max-age=86400,must-revalidate");
            context.write(response);
            context.write(new DefaultFileRegion(path.toFile(), 0, fileLength));
            context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } catch (NoSuchFileException | FileNotFoundException e) {
            sendStatus(context, HttpResponseStatus.NOT_FOUND);
        } catch (Throwable e) {
            sendStatus(context, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isBadRequest(FullHttpRequest request) {
        return !request.decoderResult().isSuccess();
    }

    private boolean isMethodNotAllowed(FullHttpRequest request) {
        return !HttpMethod.GET.equals(request.method());
    }

    private boolean isNotModified(FullHttpRequest request, long lastModified) {
        return request.headers().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, 0) / 1000 !=
                lastModified / 1000;
    }

    private void sendStatus(ChannelHandlerContext context, HttpResponseStatus status) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        context.write(response);
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
