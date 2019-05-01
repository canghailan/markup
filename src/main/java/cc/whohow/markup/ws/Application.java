package cc.whohow.markup.ws;

import cc.whohow.markup.Markup;
import cc.whohow.markup.MarkupConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.File;

public class Application {
    public static void main(String[] args) throws Exception {
        MarkupConfiguration configuration = getConfiguration(args);

        Markup markup = new Markup(configuration);
        markup.update();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.DEBUG))
                    .childHandler(new ServerInitializer(new WebServiceHandler(markup)));

            Channel channel = bootstrap.bind(configuration.getPort()).sync().channel();
            channel.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            markup.close();
        }
    }

    public static MarkupConfiguration getConfiguration(String... args) throws Exception {
        File configuration = new File("markup.yml");
        if (configuration.exists()) {
            return new ObjectMapper(new YAMLFactory())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(configuration, MarkupConfiguration.class);
        }
        throw new AssertionError();
    }
}
