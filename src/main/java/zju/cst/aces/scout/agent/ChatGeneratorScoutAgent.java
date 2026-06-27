package zju.cst.aces.scout.agent;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ChatGenerator;
import zju.cst.aces.dto.ChatResponse;
import zju.cst.aces.dto.ChatUsage;

public class ChatGeneratorScoutAgent implements ScoutAgent {
    @Override
    public ScoutAgentResult run(ScoutAgentRequest request) {
        Config config = request == null ? null : request.getConfig();
        ChatResponse response = ChatGenerator.chat(config, request == null ? null : request.getMessages());
        ChatUsage usage = response == null ? null : response.getUsage();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int responseTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new ScoutAgentResult(ChatGenerator.getContentByResponse(response), promptTokens, responseTokens);
    }
}
