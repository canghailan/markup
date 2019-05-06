package cc.whohow.markup.impl;

import java.util.Date;

/**
 * 文件元数据
 */
public class Metadata {
    /**
     * 不存在的文件
     */
    public static final Metadata NOT_FOUND = new Metadata(0, new Date(0), null);

    private long size;
    private Date lastModified;
    private String contentType;

    public Metadata(long size, Date lastModified, String contentType) {
        this.size = size;
        this.lastModified = lastModified;
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public String getContentType() {
        return contentType;
    }
}
