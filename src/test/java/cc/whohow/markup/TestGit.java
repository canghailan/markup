package cc.whohow.markup;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestGit {
    @Test
    public void test() throws Exception {
        Path path = Paths.get("markup");
        if (Files.exists(path.resolve(".git"))) {
            Git git = Git.open(path.toFile());
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call();
            git.close();
        } else {
            Git.cloneRepository()
                    .setURI("https://github.com/canghailan/markup.git")
                    .setDirectory(path.toFile())
                    .setCloneAllBranches(true)
                    .call()
                    .close();
        }
    }
}
