package zju.cst.aces.api.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRequestTimeoutTest {
    @Test
    void defaultClientUsesThreeMinuteLlmRequestTimeout() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();

        assertEquals(TimeUnit.MINUTES.toMillis(3), config.getClient().callTimeoutMillis());
    }

    @Test
    void proxyClientUsesThreeMinuteLlmRequestTimeout() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .proxy("127.0.0.1:8080")
                .build();

        assertEquals(TimeUnit.MINUTES.toMillis(3), config.getClient().callTimeoutMillis());
    }

    @Test
    void explicitClientIsNotOverwritten() {
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(11, TimeUnit.SECONDS)
                .build();

        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .client(client)
                .build();

        assertEquals(TimeUnit.SECONDS.toMillis(11), config.getClient().callTimeoutMillis());
    }

    private static class FakeProject implements Project {
        private final File basedir;

        private FakeProject(Path basedir) {
            this.basedir = basedir.toFile();
        }

        @Override
        public Project getParent() {
            return null;
        }

        @Override
        public File getBasedir() {
            return basedir;
        }

        @Override
        public String getPackaging() {
            return "jar";
        }

        @Override
        public String getGroupId() {
            return "demo";
        }

        @Override
        public String getArtifactId() {
            return "demo-artifact";
        }

        @Override
        public java.util.List<String> getCompileSourceRoots() {
            return new ArrayList<String>();
        }

        @Override
        public Path getArtifactPath() {
            return basedir.toPath().resolve("target/demo.jar");
        }

        @Override
        public Path getBuildPath() {
            return basedir.toPath().resolve("target/classes");
        }

        @Override
        public java.util.List<String> getClassPaths() {
            return new ArrayList<String>();
        }

        @Override
        public java.util.List<String> getDependencyPaths() {
            return new ArrayList<String>();
        }
    }
}
