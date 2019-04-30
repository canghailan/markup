package cc.whohow.markup;

import cc.whohow.markup.service.Searcher;
import cc.whohow.markup.model.TextFile;
import org.junit.Test;

import java.util.Date;

public class TestTextFileSearcher {
    @Test
    public void test() throws Exception {
        Searcher searcher = new Searcher();

        searcher.index(newDoc("/a/b/c", "你好，世界"));
        searcher.index(newDoc("/a/b/d", "Hello World"));
        searcher.commit();
        searcher.refresh();

        System.out.println(searcher.get("/a/b/c"));
        System.out.println(searcher.get("/a/b/e"));
        System.out.println(searcher.search("", "hello"));

        searcher.close();
    }

    private TextFile newDoc(String key, String value) {
        TextFile textFile = new TextFile();
        textFile.setPath(key);
        textFile.setContent(value);
        textFile.setLastModified(new Date());
        return textFile;
    }
}
