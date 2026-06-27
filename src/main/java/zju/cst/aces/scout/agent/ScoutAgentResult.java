package zju.cst.aces.scout.agent;

public class ScoutAgentResult {
    private final String content;
    private final int promptTokens;
    private final int responseTokens;

    public ScoutAgentResult(String content, int promptTokens, int responseTokens) {
        this.content = content == null ? "" : content;
        this.promptTokens = promptTokens;
        this.responseTokens = responseTokens;
    }

    public String getContent() {
        return content;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getResponseTokens() {
        return responseTokens;
    }
}
