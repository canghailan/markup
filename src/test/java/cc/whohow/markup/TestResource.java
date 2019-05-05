package cc.whohow.markup;

import org.junit.Test;

import java.net.URL;

public class TestResource {
    @Test
    public void test() {
        URL index = Markup.class.getResource("/index.html");
        System.out.println(index);
    }
}
