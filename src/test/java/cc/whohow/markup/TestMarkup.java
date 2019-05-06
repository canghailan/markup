package cc.whohow.markup;

import cc.whohow.markup.ws.Server;
import org.junit.Test;

import java.nio.file.Paths;

public class TestMarkup {
    @Test
    public void testPath() {
        System.out.println(Paths.get("").toAbsolutePath().normalize());
    }

    @Test
    public void test() throws Exception {
        MarkupConfiguration configuration = Server.getConfiguration();
        Markup markup = new Markup(configuration);
        markup.update();

        System.out.println(markup.list());
//        System.out.println(markup.list("", "Flutter.md", 1));
//        System.out.println(markup.get("Flutter.md"));
//        System.out.println(markup.get("Flutter.md", "Java下载.md"));


        markup.close();
    }
}
