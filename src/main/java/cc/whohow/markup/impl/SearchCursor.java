package cc.whohow.markup.impl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 搜索分页标识符
 */
public class SearchCursor {
    private String prefix;
    private String keyword;
    private int count;
    private int offset;
    private String key;

    public SearchCursor() {
    }

    public SearchCursor(String cursor) {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("\n");
        this.prefix = parts[0].substring(2);
        this.keyword = parts[1].substring(2);
        this.count = Integer.parseInt(parts[2].substring(2));
        this.offset = Integer.parseInt(parts[3].substring(2));
        this.key = parts[4].substring(2);
    }

    private String join() {
        return "p=" + (prefix == null ? "" : prefix) + "\n" +
                "q=" + (keyword == null ? "" : keyword) + "\n" +
                "n=" + (count) + "\n" +
                "o=" + (offset) + "\n" +
                "k=" + (key == null ? "" : key);
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return Base64.getUrlEncoder().encodeToString(
                join().getBytes(StandardCharsets.UTF_8))
                .replace("=", "");
    }
}
