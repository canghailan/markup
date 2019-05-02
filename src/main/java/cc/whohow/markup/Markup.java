package cc.whohow.markup;

import cc.whohow.markup.impl.*;
import com.hankcs.lucene.HanLPTokenizerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Markup implements AutoCloseable {
    private static final Logger log = LogManager.getLogger();
    private static final String KEY = "key";
    private static final String CONTENT = "content";
    private static final String HTML = "html";
    private static final String CREATED = "created";
    private static final Sort SORT_BY_CREATED = new Sort(new SortField(CREATED, SortField.Type.LONG, true));

    // executor
    private final ScheduledExecutorService executor;
    // git
    private final URI uri;
    private final Path repo;
    // lucene
    private final Directory index;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    // markdown
    private final Parser parser;
    private final HtmlRenderer renderer;
    // updater
    private final MarkupUpdater updater;
    //
    private volatile Git git;
    private volatile IndexSearcher searcher;

    public Markup(MarkupConfiguration configuration) {
        try {
            // git
            uri = URI.create(configuration.getGit());
            repo = Paths.get(getGitRepoName());
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
            // updater
            updater = new MarkupUpdater(this, executor);
        } catch (Throwable e) {
            close();
            throw new UndeclaredThrowableException(e);
        }
    }

    public void index(Markdown markdown) throws IOException {
        if (markdown == null) {
            return;
        }
        log.debug("index {}", markdown.getKey());
        writer.updateDocument(new Term(KEY, markdown.getKey()), fromMarkdown(markdown));
    }

    public void delete(Collection<String> keys) throws IOException {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Term[] terms = keys.stream()
                .map(key -> new Term(KEY, key))
                .toArray(Term[]::new);
        writer.deleteDocuments(terms);
    }

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

    public Collection<String> list() throws IOException {
        IndexSearcher searcher = this.searcher;
        IndexReader reader = searcher.getIndexReader();
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

    public Markdown get(String key) throws IOException {
        if (key == null || key.isEmpty()) {
            return null;
        }

        IndexSearcher searcher = this.searcher;
        Query q = new TermQuery(new Term(KEY, key));
        log.debug("query {}", q);

        ScoreDoc[] scoreDocs = searcher.search(q, 1).scoreDocs;
        return (scoreDocs.length == 0) ? null :
                toMarkdown(searcher.doc(scoreDocs[0].doc));
    }

    public SearchResult<Markdown> search(SearchCursor c) throws IOException {
        IndexSearcher searcher = this.searcher;

        SearchCursor nc = new SearchCursor();
        nc.setPrefix(c.getPrefix());
        nc.setKeyword(c.getKeyword());
        nc.setCount(c.getCount());
        nc.setOffset(c.getOffset() + c.getCount());

        SearchResult<Markdown> r = new SearchResult<>();
        Query q = buildSearchQuery(c.getPrefix(), c.getKeyword());
        Sort s = buildSearchSort(c.getPrefix(), c.getKeyword());
        log.debug("query {} {} {}", q, c.getKey(), c.getCount());

        ScoreDoc[] scoreDocs = searcher.search(q, nc.getOffset(), s).scoreDocs;
        LinkedList<Markdown> list = new LinkedList<>();
        for (int i = scoreDocs.length - 1; i >= Integer.max(scoreDocs.length - c.getCount(), 0); i--) {
            Document document = searcher.doc(scoreDocs[i].doc);
            String key = document.get(KEY);
            if (key.equals(c.getKey())) {
                break;
            }
            list.addFirst(toMarkdown(document));
        }
        r.setList(list);
        if (!list.isEmpty()) {
            nc.setKey(list.getLast().getKey());
            r.setCursor(nc.toString());
        }
        return r;
    }

    protected Query buildSearchQuery(String prefix, String keyword) {
        Query keyQuery = null;
        Query valueQuery = null;
        if (prefix != null && !prefix.isEmpty()) {
            keyQuery = new PrefixQuery(new Term(KEY, prefix));
        }
        if (keyword != null && !keyword.isEmpty()) {
            if (keyword.length() <= 4) {
                valueQuery = new TermQuery(new Term(CONTENT, keyword));
            } else {
                valueQuery = new FuzzyQuery(new Term(CONTENT, keyword));
            }
        }
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

    protected Sort buildSearchSort(String prefix, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return SORT_BY_CREATED;
        } else {
            return Sort.RELEVANCE;
        }
    }

    public Path resolve(String path) {
        return repo.resolve(path);
    }

    public void update() {
        updater.run();
    }

    public void updateAsync() {
        executor.submit(updater);
    }

    @Override
    public synchronized void close() {
        log.info("close");
        CloseRunnable.close(updater);
        ShutdownRunnable.shutdown(executor);
        CloseRunnable.close(searcher.getIndexReader());
        CloseRunnable.close(writer);
        CloseRunnable.close(analyzer);
        CloseRunnable.close(index);
        CloseRunnable.close(git);
    }

    public synchronized void gitUpdate() throws Exception {
        if (git == null) {
            if (Files.exists(repo.resolve(".git"))) {
                git = Git.open(repo.toFile());
            }
        }
        if (git == null) {
            gitClone();
        } else {
            gitPull();
        }
    }

    private void gitClone() throws Exception {
        log.debug("git clone {} {}", uri, repo);
        git = Git.cloneRepository()
                .setURI(uri.toString())
                .setDirectory(repo.toFile())
                .setCloneAllBranches(true)
                .call();
    }

    private void gitPull() throws Exception {
        log.debug("git pull");
        git.pull()
                .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                .call();
    }

    public RevCommit gitHead() throws IOException {
        return gitResolveCommit(Constants.HEAD);
    }

    public RevCommit gitResolveCommit(String string) throws IOException {
        Repository repository = git.getRepository();
        return repository.parseCommit(repository.resolve(string));
    }

    public Iterable<String> gitDiff(RevCommit start, RevCommit end) throws Exception {
        if (start.equals(end)) {
            return Collections.emptySet();
        }
        Repository repository = git.getRepository();
        try (ObjectReader reader = repository.newObjectReader()) {
            try (TreeWalk treeWalk = new TreeWalk(reader)) {
                try (RevWalk revWalk = new RevWalk(repository)) {
                    revWalk.markStart(start);
                    if (end != null) {
                        revWalk.markUninteresting(end);
                    }
                    for (RevCommit commit : revWalk) {
                        CanonicalTreeParser parser = new CanonicalTreeParser();
                        parser.reset(reader, commit.getTree());
                        treeWalk.addTree(parser);
                    }
                }
                treeWalk.setRecursive(true);

                Set<String> paths = new HashSet<>();
                while (treeWalk.next()) {
                    paths.add(treeWalk.getPathString());
                }
                return paths;
            }
        }
    }

    public boolean accept(String path) {
        return path.endsWith(".md");
    }

    public boolean accept(Path path) {
        return accept(path.toString());
    }

    public Markdown readMarkdown(String key) throws IOException {
        try {
            Markdown markdown = new Markdown();
            markdown.setKey(key);
            markdown.setContent(readUtf8(key));
            markdown.setHtml(render(markdown.getContent()));
            markdown.setCreated(getCreated(key));
            return markdown;
        } catch (NoSuchFileException | FileNotFoundException e) {
            return null;
        }
    }

    public ByteBuffer read(String key) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(resolve(key)));
    }

    public String readUtf8(String key) throws IOException {
        return new String(read(key).array(), StandardCharsets.UTF_8);
    }

    public String render(String markdown) {
        return renderer.render(parser.parse(markdown));
    }

    public Date getCreated(String key) throws IOException {
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            revWalk.sort(RevSort.REVERSE);
            revWalk.markStart(revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD)));
            revWalk.setTreeFilter(PathFilter.create(key));
            for (RevCommit revCommit : revWalk) {
                return new Date(revCommit.getCommitTime() * 1000L);
            }
        }
        throw new AssertionError();
    }

    protected String getGitRepoName() {
        Matcher matcher = Pattern.compile("(?<name>[^/]+)/?$")
                .matcher(uri.getPath());
        if (matcher.find()) {
            String name = matcher.group("name");
            if (name.endsWith(".git")) {
                return name.substring(0, name.length() - ".git".length());
            }
            return name;
        }
        throw new AssertionError();
    }

    protected NavigableSet<String> listGitRepo() throws IOException {
        GitRepositoryFileVisitor visitor = new GitRepositoryFileVisitor();
        Files.walkFileTree(repo, visitor);
        return visitor.getFiles().stream()
                .map(this::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    protected String getKey(Path file) {
        Path path = repo.relativize(file);
        StringJoiner joiner = new StringJoiner("/");
        for (Path p : path) {
            joiner.add(p.toString());
        }
        return joiner.toString();
    }

    protected Document fromMarkdown(Markdown markdown) {
        Document document = new Document();
        document.add(new StringField(KEY, markdown.getKey(), Field.Store.YES));
        document.add(new TextField(CONTENT, markdown.getContent(), Field.Store.YES));
        document.add(new StoredField(HTML, markdown.getHtml()));
        document.add(new StringField(CREATED, DateTools.dateToString(markdown.getCreated(), DateTools.Resolution.SECOND), Field.Store.YES));
        document.add(new SortedDocValuesField(KEY, new BytesRef(markdown.getKey())));
        document.add(new NumericDocValuesField(CREATED, markdown.getCreated().getTime()));
        return document;
    }

    protected Markdown toMarkdown(Document document) {
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