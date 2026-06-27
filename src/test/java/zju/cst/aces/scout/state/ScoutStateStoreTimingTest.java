package zju.cst.aces.scout.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.config.Config;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutStateStoreTimingTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsStateIoTimingWhenConfigIsProvided() throws Exception {
        Config config = new Config();
        config.setResourceProfileEnabled(true);
        ScoutStateStore store = new ScoutStateStore(tempDir, config);

        store.loadProjectState();
        store.saveProjectState(new ScoutProjectState());

        String timing = config.formatTimingProfile();

        assertTrue(timing.contains("state_io"));
        assertTrue(timing.contains("count="));
    }
}
