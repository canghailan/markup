package cc.whohow.markup.impl;

import java.util.List;

public class SearchResult<T> {
    private List<T> list;
    private String cursor;

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    @Override
    public String toString() {
        return list + cursor;
    }
}
