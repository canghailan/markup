package cc.whohow.markup;

import cc.whohow.markup.model.TextFile;
import cc.whohow.markup.service.TextFileManager;
import org.junit.Test;

public class TestTextFileDirectory {
    @Test
    public void test() throws Exception {
        TextFileManager textFileManager = new TextFileManager("D:\\git\\notes");

        textFileManager.list("", 10).stream()
                .map(TextFile::getPath)
                .forEach(System.out::println);
//        System.out.println(docDirectory.get("Flutter.md"));
//        System.out.println(docDirectory.get("Flutter1.md"));
//        System.out.println(docDirectory.search("", "docker"));
//        Thread.sleep(3000);
//        System.out.println(docDirectory.search("", "docker"));

        textFileManager.close();
    }
}
