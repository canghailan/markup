package cc.whohow.markup;

import cc.whohow.markup.impl.ContentTypes;
import cc.whohow.markup.impl.GitRepositoryFileVisitor;
import cc.whohow.markup.impl.Metadata;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Git仓库
 */
public class MarkupGitRepository implements AutoCloseable {
    private static final Logger log = LogManager.getLogger();
    // git
    private final URI uri;
    private final Path repo;
    // metadata
    private final Cache<String, Metadata> metadataCache;
    // mutable git
    private volatile Git git;
    // state
    private volatile boolean updating;

    public MarkupGitRepository(MarkupConfiguration configuration) {
        uri = URI.create(configuration.getGit());
        repo = Paths.get(getGitName());
        metadataCache = CacheBuilder.newBuilder()
                .maximumSize(1024)
                .build();
        updating = false;
    }

    public Path resolve(String path) {
        return repo.resolve(path);
    }

    public synchronized void gitUpdate() throws Exception {
        if (git == null) {
            if (Files.exists(repo.resolve(".git"))) {
                git = Git.open(repo.toFile());
            }
        }
        try {
            updating = true;
            if (git == null) {
                gitClone();
            } else {
                gitPull();
            }
        } finally {
            updating = false;
            metadataCache.invalidateAll();
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

    public RevCommit getHeadCommit() throws IOException {
        return gitResolveCommit(Constants.HEAD);
    }

    public RevCommit getFirstCommit() throws IOException {
        return getFirstCommit(null);
    }

    /**
     * 读取第一次提交
     */
    private RevCommit getFirstCommit(String key) throws IOException {
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            revWalk.sort(RevSort.REVERSE);
            revWalk.markStart(revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD)));
            if (!Strings.isNullOrEmpty(key)) {
                revWalk.setTreeFilter(PathFilter.create(key));
            }
            return revWalk.next();
        }
    }

    public RevCommit gitResolveCommit(String commit) throws IOException {
        Repository repository = git.getRepository();
        return repository.parseCommit(repository.resolve(commit));
    }

    /**
     * 比较2次提交
     */
    public List<DiffEntry> gitDiff(RevCommit oldCommit, RevCommit newCommit) throws IOException {
        if (oldCommit == null) {
            oldCommit = getFirstCommit();
        }
        if (newCommit.equals(oldCommit)) {
            return Collections.emptyList();
        }

        Repository repository = git.getRepository();
        try (DiffFormatter diff = new DiffFormatter(NullOutputStream.INSTANCE)) {
            diff.setRepository(repository);
            return diff.scan(oldCommit, newCommit);
        }
    }

    /**
     * 文件列表
     */
    public SortedSet<String> list() throws IOException {
        GitRepositoryFileVisitor visitor = new GitRepositoryFileVisitor();
        Files.walkFileTree(repo, visitor);
        return visitor.getFiles().stream()
                .map(this::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public Metadata getMetadata(String key) throws Exception {
        if (updating) {
            return readMetadata(key);
        } else {
            return metadataCache.get(key, () -> readMetadata(key));
        }
    }

    private Metadata readMetadata(String key) throws IOException {
        Path path = resolve(key);
        if (Files.exists(path)) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String contentType = ContentTypes.probeContentType(path);
            return new Metadata(attributes.size(), new Date(attributes.lastModifiedTime().toMillis()), contentType);
        } else {
            return Metadata.NOT_FOUND;
        }
    }

    public ByteBuffer read(String key) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(resolve(key)));
    }

    public String readUtf8(String key) throws IOException {
        return new String(read(key).array(), StandardCharsets.UTF_8);
    }

    /**
     * Git文件创建时间不可靠，读取第一次提交时间
     */
    public Date getCreated(String key) throws IOException {
        return new Date(getFirstCommit(key).getCommitTime() * 1000L);
    }

    private String getGitName() {
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

    private String getKey(Path file) {
        Path path = repo.relativize(file);
        StringJoiner joiner = new StringJoiner("/");
        for (Path p : path) {
            joiner.add(p.toString());
        }
        return joiner.toString();
    }

    @Override
    public void close() throws Exception {
        if (git != null) {
            git.close();
        }
    }
}
