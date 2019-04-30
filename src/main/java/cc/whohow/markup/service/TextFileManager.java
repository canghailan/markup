package cc.whohow.markup.service;

import cc.whohow.markup.model.TextFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class TextFileManager implements AutoCloseable {
    private static final Logger log = LogManager.getLogger();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd/HHmmss");
    private final Path root;
    private final Searcher searcher;
    private final NavigableMap<String, String> list = new TreeMap<>();
    private final ConcurrentMap<String, TextFile> cache = new ConcurrentHashMap<>();

    public TextFileManager(String root) {
        this(Paths.get(root));
    }

    public TextFileManager(Path root) {
        try {
            this.root = root;
            this.searcher = new Searcher();
            this.initialize();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<TextFile> list(String next, int size) {
        String sortKey = (next == null) ? "" : next;
        return list.tailMap(sortKey).values().stream()
                .limit(size)
                .map(this::get)
                .collect(Collectors.toList());
    }

    public List<TextFile> search(String prefix, String keyword) {
        try {
            return searcher.search(prefix, keyword);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public Path resolve(String path) {
        return root.resolve(path.substring(1));
    }

    private void initialize() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, this::accept)) {
            for (Path path : stream) {
                list.put(getSortKey(path), getKey(path));
            }
            CompletableFuture.runAsync(this::index);
        }
    }

    private void index() {
        try {
            list.values().parallelStream()
                    .map(this::get)
                    .forEach(this::index);
            searcher.commit();
            searcher.refresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void index(TextFile textFile) {
        try {
            searcher.index(textFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String getKey(Path path) {
        StringJoiner joiner = new StringJoiner("/");
        for (Path p : root.relativize(path)) {
            joiner.add(p.getFileName().toString());
        }
        return joiner.toString();
    }

    private String getSortKey(Path path) {
        try {
            return format(Files.getLastModifiedTime(path)) +
                    "/" +
                    getKey(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String format(FileTime time) {
        return DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault()));
    }

    private Path getPath(String key) {
        return root.resolve(key);
    }

    private TextFile read(String key) {
        try {
            Path path = getPath(key);
            TextFile textFile = new TextFile();
            textFile.setPath(key);
            textFile.setContent(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            textFile.setLastModified(new Date(Files.getLastModifiedTime(path).toMillis()));
            return textFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TextFile get(String key) {
        try {
            return cache.computeIfAbsent(key, this::read);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        searcher.close();
    }

    protected boolean accept(Path path) {
        try {
            return Files.isRegularFile(path) &&
                    Files.probeContentType(path).startsWith("text/");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
