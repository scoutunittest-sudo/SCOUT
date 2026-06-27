package zju.cst.aces.util;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.status.StatusOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AskGPTApiKeyTest {
    @Test
    void addsBearerAuthorizationHeaderWhenApiKeyIsConfigured() {
        AtomicReference<String> authorization = new AtomicReference<>();
        Config config = config("test-api-key", authorization);

        new AskGPT(config).askChatGPT(Collections.singletonList(ChatMessage.of("hello")));

        assertEquals("Bearer test-api-key", authorization.get());
        assertEquals(1, config.getLlmCallAttemptCount().get());
        assertEquals(1, config.getLlmCallSuccessCount().get());
    }

    @Test
    void omitsAuthorizationHeaderWhenApiKeyIsNoApi() {
        AtomicReference<String> authorization = new AtomicReference<>();
        Config config = config("NO_API", authorization);

        new AskGPT(config).askChatGPT(Collections.singletonList(ChatMessage.of("hello")));

        assertEquals(null, authorization.get());
    }

    @Test
    void countsEveryFailedHttpRetryAsAttemptWithoutSuccess() {
        Config config = config("test-api-key", new AtomicReference<>());
        config.setLogger(new NoOpLogger());
        config.setClient(new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Server Error")
                        .body(ResponseBody.create(MediaType.parse("text/plain"), "failed"))
                        .build())
                .build());

        new AskGPT(config).askChatGPT(Collections.singletonList(ChatMessage.of("hello")));

        assertEquals(5, config.getLlmCallAttemptCount().get());
        assertEquals(0, config.getLlmCallSuccessCount().get());
    }

    @Test
    void defersHttpFailureDetailsAfterAllRetriesFail() {
        Config config = config("test-api-key", new AtomicReference<>());
        config.setLogger(new NoOpLogger());
        config.setClient(new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body(ResponseBody.create(MediaType.parse("application/json"),
                                "{\"error\":{\"message\":\"invalid project key\"}}"))
                        .build())
                .build());

        new AskGPT(config).askChatGPT(Collections.singletonList(ChatMessage.of("hello")));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StatusOutput.flushDiagnostics(new PrintStream(output));
        String diagnostic = output.toString();
        org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("HTTP 401"));
        org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("invalid project key"));
    }

    private Config config(String apiKey, AtomicReference<String> authorization) {
        Config config = new Config();
        config.setClient(new OkHttpClient.Builder()
                .addInterceptor(new CapturingInterceptor(authorization))
                .build());
        config.setUrl("https://api.openai.com/v1/chat/completions");
        config.setModelName("gpt-4o-mini");
        config.setTemperature(0.5);
        config.setFrequencyPenalty(0);
        config.setPresencePenalty(0);
        config.setMaxResponseTokens(16);
        config.setApiKey(apiKey);
        config.setApiKeys(new String[]{apiKey});
        config.setSleepTime(0);
        config.setLogger(new NoOpLogger());
        return config;
    }

    private static class CapturingInterceptor implements Interceptor {
        private static final MediaType JSON = MediaType.parse("application/json");
        private final AtomicReference<String> authorization;

        private CapturingInterceptor(AtomicReference<String> authorization) {
            this.authorization = authorization;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            authorization.set(chain.request().header("Authorization"));
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(JSON, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"))
                    .build();
        }
    }

    private static class NoOpLogger implements Logger {
        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg) {
        }

        @Override
        public void debug(String msg) {
        }
    }
}
