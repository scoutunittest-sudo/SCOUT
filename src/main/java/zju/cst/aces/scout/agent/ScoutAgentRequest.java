package zju.cst.aces.scout.agent;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScoutAgentRequest {
    private final Config config;
    private final ScoutAgentTask task;
    private final List<ChatMessage> messages;

    public ScoutAgentRequest(Config config, ScoutAgentTask task, List<ChatMessage> messages) {
        this.config = config;
        this.task = task;
        this.messages = messages == null
                ? Collections.<ChatMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    public Config getConfig() {
        return config;
    }

    public ScoutAgentTask getTask() {
        return task;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }
}
