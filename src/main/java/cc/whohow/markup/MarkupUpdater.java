package cc.whohow.markup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.concurrent.ExecutorService;

public class MarkupUpdater implements Runnable, AutoCloseable {
    private static final Logger log = LogManager.getLogger();

    private final Markup markup;
    private final ExecutorService executor;

    private volatile RevCommit committed;

    public MarkupUpdater(Markup markup, ExecutorService executor) {
        this.markup = markup;
        this.executor = executor;
    }

    @Override
    public void run() {
        try {
            update();
        } catch (Exception e) {
            log.error("update", e);
        }
    }

    private void update() throws Exception {
        int updated = 0;
        markup.gitUpdate();
        RevCommit head = markup.gitHead();
        for (String file : markup.gitDiff(head, committed)) {
            if (markup.accept(file)) {
                Markdown markdown = markup.readMarkdown(file);
                if (markdown != null) {
                    markup.index(markdown);
                    updated++;
                }
            }
        }
        if (updated > 0) {
            markup.commit();
        }
        committed = head;
    }

    @Override
    public void close() throws Exception {
    }
}
