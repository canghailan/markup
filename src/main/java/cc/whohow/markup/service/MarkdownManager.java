package cc.whohow.markup.service;

import java.nio.file.Files;
import java.nio.file.Path;

public class MarkdownManager extends TextFileManager {
    public MarkdownManager(String root) {
        super(root);
    }

    public String toHtml(String key) {
        return null;
    }

    @Override
    protected boolean accept(Path path) {
        return Files.isRegularFile(path) &&
                path.getFileName().toString().toLowerCase().endsWith(".md");
    }
}
