package cc.whohow.markup;

import cc.whohow.markup.impl.CloseRunnable;
import cc.whohow.markup.impl.HanLPPinyinTokenFilterFactory;
import cc.whohow.markup.impl.SearchCursor;
import cc.whohow.markup.impl.SearchResult;
import com.google.common.base.Strings;
import com.hankcs.lucene.HanLPTokenizerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.NoSuchFileException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Markup implements AutoCloseable {
    private static final Logger log = LogManager.getLogger();
    private static final String KEY = "key";
    private static final String CONTENT = "content";
    private static final String HTML = "html";
    private static final String CREATED = "created";
    private static final Sort SORT_BY_CREATED = new Sort(new SortField(CREATED, SortField.Type.LONG, true));

    // git
    private final MarkupGitRepository gitRepository;
    // executor
    private final ScheduledExecutorService executor;
    // lucene
    private final Directory index;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    // markdown
    private final Parser parser;
    private final HtmlRenderer renderer;
    // mutable searcher
    private volatile IndexSearcher searcher;
    // state
    private volatile RevCommit committed;

    public Markup(MarkupConfiguration configuration) {
        try {
            // git
            gitRepository = new MarkupGitRepository(configuration);
            // lucene
            index = new ByteBuffersDirectory();
            analyzer = CustomAnalyzer.builder()
                    .withTokenizer(HanLPTokenizerFactory.class)
                    .addTokenFilter(LowerCaseFilterFactory.class)
                    .addTokenFilter(HanLPPinyinTokenFilterFactory.class)
                    .build();
            writer = new IndexWriter(index, new IndexWriterConfig(analyzer));
            writer.commit();
            searcher = new IndexSearcher(DirectoryReader.open(index));
            // markdown
            List<Extension> extensions = Collections.singletonList(TablesExtension.create());
            parser = Parser.builder()
                    .extensions(extensions)
                    .build();
            renderer = HtmlRenderer.builder()
                    .extensions(extensions)
                    .build();
            // executor
            executor = Executors.newScheduledThreadPool(1);
        } catch (Throwable e) {
            close();
            throw new UndeclaredThrowableException(e);
        }
    }

    private static void close(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable e) {
            log.error("close", e);
        }
    }

    private static void shutdown(ExecutorService executor) {
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor.awaitTermination(3, TimeUnit.SECONDS);
            }
        } catch (Throwable e) {
            log.error("close", e);
        }
    }

    public MarkupGitRepository getGitRepository() {
        return gitRepository;
    }

    /**
     * 索引Markdown
     */
    public void index(Markdown markdown) throws IOException {
        if (markdown == null) {
            return;
        }
        log.debug("index {}", markdown.getKey());
        writer.updateDocument(new Term(KEY, markdown.getKey()), fromMarkdown(markdown));
    }

    /**
     * 删除内容
     */
    public void delete(Collection<String> keys) throws IOException {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        log.debug("delete {}", keys);
        Term[] terms = keys.stream()
                .map(key -> new Term(KEY, key))
                .toArray(Term[]::new);
        writer.deleteDocuments(terms);
    }

    /**
     * 提交变更
     */
    public synchronized void commit() throws IOException {
        log.debug("commit");
        writer.flush();
        writer.commit();
        DirectoryReader reader = (DirectoryReader) searcher.getIndexReader();
        DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            log.debug("reopen");
            searcher = new IndexSearcher(newReader);
            executor.schedule(new CloseRunnable(reader), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * 目录
     */
    public SortedSet<String> list() throws IOException {
        log.debug("list");
        IndexSearcher searcher = this.searcher;
        IndexReader reader = searcher.getIndexReader();
        // 读取正向索引
        SortedSet<String> keys = new TreeSet<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            LeafReader leafReader = leaf.reader();
            SortedDocValues docValues = DocValues.getSorted(leafReader, KEY);
            while (true) {
                int doc = docValues.nextDoc();
                if (doc == SortedDocValues.NO_MORE_DOCS) {
                    break;
                }
                keys.add(docValues.binaryValue().utf8ToString());
            }
        }
        return keys;
    }

    /**
     * 读取Markdown
     */
    public Markdown get(String key) throws IOException {
        if (Strings.isNullOrEmpty(key)) {
            return null;
        }

        IndexSearcher searcher = this.searcher;
        Query query = new TermQuery(new Term(KEY, key));
        log.debug("query {}", query);

        ScoreDoc[] scoreDocs = searcher.search(query, 1).scoreDocs;
        return (scoreDocs.length == 0) ? null :
                toMarkdown(searcher.doc(scoreDocs[0].doc));
    }

    public SearchResult<Markdown> search(SearchCursor cursor) throws IOException {
        IndexSearcher searcher = this.searcher;

        SearchCursor next = new SearchCursor();
        next.setPrefix(cursor.getPrefix());
        next.setKeyword(cursor.getKeyword());
        next.setCount(cursor.getCount());
        next.setOffset(cursor.getOffset() + cursor.getCount());

        SearchResult<Markdown> result = new SearchResult<>();
        Query query = buildSearchQuery(cursor.getPrefix(), cursor.getKeyword());
        Sort sort = buildSearchSort(cursor.getPrefix(), cursor.getKeyword());
        log.debug("query {} {} {}", query, cursor.getKey(), cursor.getCount());

        ScoreDoc[] scoreDocs = searcher.search(query, next.getOffset(), sort).scoreDocs;
        LinkedList<Markdown> list = new LinkedList<>();
        for (int i = scoreDocs.length - 1; i >= Integer.max(scoreDocs.length - cursor.getCount(), 0); i--) {
            Document document = searcher.doc(scoreDocs[i].doc);
            String key = document.get(KEY);
            if (key.equals(cursor.getKey())) {
                break;
            }
            list.addFirst(toMarkdown(document));
        }
        result.setList(list);
        if (!list.isEmpty()) {
            next.setKey(list.getLast().getKey());
            result.setCursor(next.toString());
        }
        return result;
    }

    protected Query buildSearchQuery(String prefix, String keyword) throws IOException {
        int n = 0;
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        if (!Strings.isNullOrEmpty(prefix)) {
            builder.add(new PrefixQuery(new Term(KEY, prefix)), BooleanClause.Occur.FILTER);
            n++;
        }
        if (!Strings.isNullOrEmpty(keyword)) {
            // 分词
            try (TokenStream tokenStream = analyzer.tokenStream(CONTENT, keyword)) {
                CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
                tokenStream.reset();
                while (tokenStream.incrementToken()) {
                    String term = charTermAttribute.toString();
                    if (term.length() > FuzzyQuery.defaultMaxEdits) {
                        builder.add(new FuzzyQuery(new Term(CONTENT, term)), BooleanClause.Occur.MUST);
                    } else {
                        builder.add(new TermQuery(new Term(CONTENT, term)), BooleanClause.Occur.MUST);
                    }
                    n++;
                }
            }
        }
        if (n == 0) {
            // 没有条件，搜索全部
            return new MatchAllDocsQuery();
        }
        return builder.build();
    }

    protected Sort buildSearchSort(String prefix, String keyword) {
        if (Strings.isNullOrEmpty(keyword)) {
            // 无关键词，按创建时间
            return SORT_BY_CREATED;
        } else {
            // 有关键词，按相关性
            return Sort.RELEVANCE;
        }
    }

    public boolean accept(String key) {
        // 只索引md文件
        return key.endsWith(".md");
    }

    public synchronized void update() throws Exception {
        // 更新
        Set<String> indexKeys = new HashSet<>();
        Set<String> deleteKeys = new HashSet<>();
        gitRepository.gitUpdate();
        RevCommit head = gitRepository.getHeadCommit();
        for (DiffEntry diffEntry : gitRepository.gitDiff(committed, head)) {
            switch (diffEntry.getChangeType()) {
                case ADD:
                case MODIFY:
                case COPY: {
                    if (accept(diffEntry.getNewPath())) {
                        indexKeys.add(diffEntry.getNewPath());
                    }
                    break;
                }
                case DELETE: {
                    if (accept(diffEntry.getNewPath())) {
                        deleteKeys.add(diffEntry.getOldPath());
                    }
                    break;
                }
                case RENAME: {
                    if (accept(diffEntry.getNewPath())) {
                        indexKeys.add(diffEntry.getNewPath());
                    }
                    if (accept(diffEntry.getNewPath())) {
                        deleteKeys.add(diffEntry.getOldPath());
                    }
                    break;
                }
                default: {
                    throw new AssertionError();
                }
            }
        }

        // TODO 多线程优化
        for (String key : indexKeys) {
            index(readMarkdown(key));
        }
        delete(deleteKeys);
        if (!indexKeys.isEmpty() || !deleteKeys.isEmpty()) {
            // 有新增或删除，提交更新
            commit();
        }
        committed = head;
    }

    @Override
    public synchronized void close() {
        log.info("close");
        shutdown(executor);
        if (searcher != null) {
            close(searcher.getIndexReader());
        }
        close(writer);
        close(analyzer);
        close(index);
        close(gitRepository);
    }

    public Markdown readMarkdown(String key) throws IOException {
        try {
            Markdown markdown = new Markdown();
            markdown.setKey(key);
            markdown.setContent(gitRepository.readUtf8(key));
            markdown.setHtml(render(markdown.getContent()));
            markdown.setCreated(gitRepository.getCreated(key));
            return markdown;
        } catch (NoSuchFileException | FileNotFoundException e) {
            return null;
        }
    }

    /**
     * 将Markdown转为HTML
     */
    private String render(String markdown) {
        return renderer.render(parser.parse(markdown));
    }

    private Document fromMarkdown(Markdown markdown) {
        Document document = new Document();
        document.add(new StringField(KEY, markdown.getKey(), Field.Store.YES));
        document.add(new TextField(CONTENT, markdown.getContent(), Field.Store.YES));
        document.add(new StoredField(HTML, markdown.getHtml()));
        document.add(new StringField(CREATED, DateTools.dateToString(markdown.getCreated(), DateTools.Resolution.SECOND), Field.Store.YES));
        // KEY 正向索引，提供目录查询
        document.add(new SortedDocValuesField(KEY, new BytesRef(markdown.getKey())));
        // CREATED 正向索引，提供排序
        document.add(new NumericDocValuesField(CREATED, markdown.getCreated().getTime()));
        return document;
    }

    private Markdown toMarkdown(Document document) {
        try {
            Markdown markdown = new Markdown();
            markdown.setKey(document.get(KEY));
            markdown.setContent(document.get(CONTENT));
            markdown.setHtml(document.get(HTML));
            markdown.setCreated(DateTools.stringToDate(document.get(CREATED)));
            return markdown;
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }
}