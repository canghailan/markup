package cc.whohow.markup.ws;

import cc.whohow.markup.Markup;
import cc.whohow.markup.MarkupConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.common.base.Strings;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Scanner;

/**
 * 服务器
 */
public class Server {
    private static final String CONFIGURATION_FILE = "markup.yml";
    private static final String MARKUP_GIT = "MARKUP_GIT";
    private static final String MARKUP_PORT = "MARKUP_PORT";

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

    /**
     * 读取配置文件
     */
    public static MarkupConfiguration getConfiguration(String... args) throws Exception {
        MarkupConfiguration configuration = mergeConfiguration(
                getArgsConfiguration(args),
                getEnvConfiguration(),
                getFileConfiguration(),
                getDefaultConfiguration());
        if (Strings.isNullOrEmpty(configuration.getGit())) {
            return readConfiguration();
        }
        return configuration;
    }

    private static MarkupConfiguration getDefaultConfiguration() {
        MarkupConfiguration markupConfiguration = new MarkupConfiguration();
        markupConfiguration.setPort(80);
        return markupConfiguration;
    }

    private static MarkupConfiguration getArgsConfiguration(String[] args) {
        // TODO
        return null;
    }

    private static MarkupConfiguration getEnvConfiguration() {
        String git = System.getenv(MARKUP_GIT);
        String port = System.getenv(MARKUP_PORT);

        MarkupConfiguration markupConfiguration = new MarkupConfiguration();
        markupConfiguration.setGit(git);
        if (!Strings.isNullOrEmpty(port)) {
            markupConfiguration.setPort(Integer.parseInt(port));
        }
        return markupConfiguration;
    }

    private static MarkupConfiguration getFileConfiguration() throws Exception {
        File configuration = new File(CONFIGURATION_FILE);
        if (configuration.exists()) {
            return new ObjectMapper(new YAMLFactory())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(configuration, MarkupConfiguration.class);
        }
        return null;
    }

    private static MarkupConfiguration readConfiguration() {
        MarkupConfiguration markupConfiguration = getDefaultConfiguration();

        Scanner scanner = new Scanner(System.in);
        while (Strings.isNullOrEmpty(markupConfiguration.getGit())) {
            System.out.print("git: ");
            markupConfiguration.setGit(scanner.next().trim());
        }
        try {
            byte[] bytes = new ObjectMapper(new YAMLFactory()
                    .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false)
                    .configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true))
                    .writeValueAsBytes(markupConfiguration);
            Files.write(Paths.get(CONFIGURATION_FILE), bytes,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignore) {
        }
        return markupConfiguration;
    }

    private static MarkupConfiguration mergeConfiguration(MarkupConfiguration... configurations) {
        MarkupConfiguration markupConfiguration = new MarkupConfiguration();
        markupConfiguration.setGit(Arrays.stream(configurations)
                .filter(Objects::nonNull)
                .map(MarkupConfiguration::getGit)
                .filter(string -> !Strings.isNullOrEmpty(string))
                .findFirst()
                .orElse(null));
        markupConfiguration.setPort(Arrays.stream(configurations)
                .filter(Objects::nonNull)
                .mapToInt(MarkupConfiguration::getPort)
                .filter(i -> i != 0)
                .findFirst()
                .orElse(0));
        return markupConfiguration;
    }
}
