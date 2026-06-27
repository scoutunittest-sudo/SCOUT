package zju.cst.aces.scout.agent;

import zju.cst.aces.api.config.Config;

public final class ScoutAgents {
    private ScoutAgents() {
    }

    public static ScoutAgent resolve(Config config) {
        if (config != null && config.getScoutAgent() != null) {
            return config.getScoutAgent();
        }
        return new ChatGeneratorScoutAgent();
    }
}
