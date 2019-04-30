package cc.whohow.markup.service;

import cc.whohow.markup.model.TextFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Searcher implements AutoCloseable {
    private static final Logger log = LogManager.getLogger();
    private static final int MAX = 100;

    private final Directory index;
    private final Analyzer analyzer ;
    private final IndexWriter writer;
    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;

    public Searcher() throws IOException {
        index = new ByteBuffersDirectory();
        analyzer = new StandardAnalyzer();
        writer = new IndexWriter(index, new IndexWriterConfig(analyzer));
        writer.commit();
        reader = DirectoryReader.open(index);
        searcher = new IndexSearcher(reader);
    }

    public void index(TextFile textFile) throws IOException {
        log.debug("INDEX {}", textFile.getPath());
        writer.addDocument(fromDoc(textFile));
    }

    public void index(TextFile... textFileList) throws IOException {
        for (TextFile textFile : textFileList) {
            index(textFile);
        }
    }

    public void index(List<TextFile> textFileList) throws IOException {
        for (TextFile textFile : textFileList) {
            index(textFile);
        }
    }

    public TextFile get(String key) throws IOException {
        Query query = new TermQuery(new Term("key", key));
        log.debug("QUERY {}", query);
        ScoreDoc[] scoreDocs = searcher.search(query, 1).scoreDocs;
        if (scoreDocs.length == 0) {
            return null;
        }
        return toDoc(searcher.doc(scoreDocs[0].doc));
    }

    public List<TextFile> search(String prefix, String keyword) throws IOException {
        Query query = buildQuery(prefix, keyword);
        log.debug("QUERY {}", query);
        TopDocs topDocs = searcher.search(query, MAX);
        List<TextFile> list = new ArrayList<>(topDocs.scoreDocs.length);
        for(ScoreDoc scoreDoc : topDocs.scoreDocs) {
            list.add(toDoc(searcher.doc(scoreDoc.doc)));
        }
        return list;
    }

    private Query buildQuery(String prefix, String keyword) {
        Query keyQuery = (prefix == null || prefix.isEmpty()) ? null :
                new PrefixQuery(new Term("key", prefix));
        Query valueQuery = (keyword == null || keyword.isEmpty()) ? null :
                new FuzzyQuery(new Term("value", keyword));
        if (keyQuery != null && valueQuery != null) {
            return new BooleanQuery.Builder()
                    .add(keyQuery, BooleanClause.Occur.FILTER)
                    .add(valueQuery, BooleanClause.Occur.MUST)
                    .build();
        }
        if (keyQuery != null) {
            return keyQuery;
        }
        if (valueQuery != null) {
            return valueQuery;
        }
        return new MatchAllDocsQuery();
    }

    public synchronized void commit() throws IOException {
        log.debug("COMMIT");
        writer.flush();
        writer.commit();
    }

    public synchronized void refresh() throws IOException {
        DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            log.debug("REOPEN");
            searcher = new IndexSearcher(newReader);
            reader.close();
            reader = newReader;
        }
    }

    @Override
    public void close() throws Exception {
        log.debug("CLOSE");
        IOUtils.close(reader, writer, analyzer, index);
    }

    protected Document fromDoc(TextFile textFile) {
        Document document = new Document();
        document.add(new StringField("key", textFile.getPath(), Field.Store.YES));
        document.add(new TextField("value", textFile.getContent(), Field.Store.YES));
        document.add(new DateTimeField("lastUpdateTime", textFile.getLastModified()));
        return document;
    }

    protected TextFile toDoc(Document document) {
        try {
            TextFile textFile = new TextFile();
            textFile.setPath(document.get("key"));
            textFile.setContent(document.get("value"));
            textFile.setLastModified(DateTools.stringToDate(document.get("lastUpdateTime")));
            return textFile;
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    static class DateTimeField extends Field {
        private static final FieldType TYPE_STORED = new FieldType();
        static {
            TYPE_STORED.setStored(true);
            TYPE_STORED.setTokenized(false);
            TYPE_STORED.freeze();
        }

        DateTimeField(String name, Date dateTime) {
            super(name, DateTools.dateToString(dateTime, DateTools.Resolution.SECOND), TYPE_STORED);
        }
    }
}