package uz.xtreme.flowdesigner;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * Both the login provider and the flows repository are part of the context: an
 * instance missing either refuses to start, so the test supplies them the way a
 * deployment does. The repository is a bare one with no commits — the same state
 * a freshly created remote is in.
 */
@SpringBootTest(properties = {
        "app.oauth2.provider=gitlab",
        "app.oauth2.gitlab.client-id=test-client-id",
        "app.oauth2.gitlab.client-secret=test-client-secret"
})
class FlowDesignerApplicationTests {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void gitProperties(DynamicPropertyRegistry registry) throws GitAPIException {
        Path remote = tempDir.resolve("remote.git");
        Git.init().setDirectory(remote.toFile()).setBare(true).call().close();

        registry.add("app.git.remote-url", () -> remote.toUri().toString());
        registry.add("app.git.main-repo-path", () -> tempDir.resolve("main").toString());
        registry.add("app.git.workspaces-path", () -> tempDir.resolve("workspaces").toString());
    }

    @Test
    void contextLoads() {
    }
}
