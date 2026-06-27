package zju.cst.aces.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.dto.ChatResponse;
import zju.cst.aces.status.StatusOutput;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AskGPT {
    private static final MediaType MEDIA_TYPE = MediaType.parse("application/json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public Config config;

    public AskGPT(Config config) {
        this.config = config;
    }

    public ChatResponse askChatGPT(List<ChatMessage> chatMessages) {
        int maxTry = 5;
        String lastFailure = "unknown LLM request failure";
        while (maxTry > 0) {
            Response response = null;
            try {
                Map<String, Object> payload = new HashMap<>();

//                if (Objects.equals(config.getModel(), "code-llama") || Objects.equals(config.getModel(), "code-llama-13B")) {
//                    payload.put("max_tokens", 8092);
//                }

                // Use direct modelName and url from config (bypass Model enum)
                String modelName;
                String urlStr;
                
                if (config.getModelName() != null) {
                    modelName = config.getModelName();
                } else if (config.getModel() != null) {
                    modelName = config.getModel().getDefaultConfig().getModelName();
                } else {
                    throw new RuntimeException("No model name configured");
                }
                
                if (config.getUrl() != null) {
                    urlStr = config.getUrl();
                } else if (config.getModel() != null) {
                    urlStr = config.getModel().getDefaultConfig().getUrl();
                } else {
                    throw new RuntimeException("No URL configured for model: " + modelName);
                }

                payload.put("messages", chatMessages);
                payload.put("model", modelName);
                payload.put("temperature", config.getTemperature());
                payload.put("frequency_penalty", config.getFrequencyPenalty());
                payload.put("presence_penalty", config.getPresencePenalty());
                payload.put("max_tokens", config.getMaxResponseTokens());
                String jsonPayload = GSON.toJson(payload);

                RequestBody body = RequestBody.create(MEDIA_TYPE, jsonPayload);
                Request.Builder requestBuilder = new Request.Builder()
                        .url(urlStr)
                        .post(body)
                        .addHeader("Content-Type", "application/json");
                String apiKey = configuredApiKey();
                if (!Config.NO_API.equals(apiKey)) {
                    requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
                }
                Request request = requestBuilder.build();
                // Request request = new Request.Builder().url(urlStr).post(body).addHeader("Content-Type", "application/json").addHeader("Authorization", "Bearer Dummy").build();
                // System.out.println(chatMessages);
                // System.out.println(config.getTemperature());
                // System.out.println(config.getFrequencyPenalty());
                // System.out.println(config.getPresencePenalty());
                // System.out.println(config.getMaxPromptTokens());
                // System.out.println(jsonPayload);
                // System.out.println(request);
                // System.out.println(urlStr);
                // System.out.println(ModelConfig);
                // System.out.println(body);
                // System.out.println(modelName);

                if (config != null) {
                    config.markLlmCallAttempt();
                }
                response = config.getClient().newCall(request).execute();
                if (!response.isSuccessful()) {
                    throw new IOException(httpFailure(response));
                }
                try {
                    Thread.sleep(config.sleepTime);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("In AskGPT.askChatGPT: " + ie);
                }
                if (response.body() == null) throw new IOException("Response body is null.");
                ChatResponse chatResponse = GSON.fromJson(response.body().string(), ChatResponse.class);
                if (config != null) {
                    config.markLlmCallSuccess();
                }
                response.close();
                return chatResponse;
            } catch (IOException e) {
                lastFailure = safeFailureMessage(e);
                if (response != null) {
                    response.close();
                }
                config.getLogger().error("In AskGPT.askChatGPT: " + e);
                maxTry--;
            }
        }
        StatusOutput.deferDiagnostic("LLM request failed after 5 attempts: " + lastFailure);
        config.getLogger().debug("AskGPT: Failed to get response\n");
        return null;


//         String apiKey = config.getRandomKey();
//         int maxTry = 5;
//         while (maxTry > 0) {
//             Response response = null;
//             try {
//                 Map<String, Object> payload = new HashMap<>();

// //                if (Objects.equals(config.getModel(), "code-llama") || Objects.equals(config.getModel(), "code-llama-13B")) {
// //                    payload.put("max_tokens", 8092);
// //                }

//                 ModelConfig modelConfig = config.getModel().getDefaultConfig();

//                 payload.put("messages", chatMessages);
//                 payload.put("model", modelConfig.getModelName());
//                 payload.put("temperature", config.getTemperature());
//                 payload.put("frequency_penalty", config.getFrequencyPenalty());
//                 payload.put("presence_penalty", config.getPresencePenalty());
//                 payload.put("max_tokens", config.getMaxResponseTokens());
//                 String jsonPayload = GSON.toJson(payload);

//                 RequestBody body = RequestBody.create(MEDIA_TYPE, jsonPayload);
//                 Request request = new Request.Builder().url(modelConfig.getUrl()).post(body).addHeader("Content-Type", "application/json").addHeader("Authorization", "Bearer " + apiKey).build();

//                 response = config.getClient().newCall(request).execute();
//                 if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
//                 try {
//                     Thread.sleep(config.sleepTime);
//                 } catch (InterruptedException ie) {
//                     throw new RuntimeException("In AskGPT.askChatGPT: " + ie);
//                 }
//                 if (response.body() == null) throw new IOException("Response body is null.");
//                 ChatResponse chatResponse = GSON.fromJson(response.body().string(), ChatResponse.class);
//                 response.close();
//                 return chatResponse;
//             } catch (IOException e) {
//                 if (response != null) {
//                     response.close();
//                 }
//                 config.getLogger().error("In AskGPT.askChatGPT: " + e);
//                 maxTry--;
//             }
//         }
//         config.getLogger().debug("AskGPT: Failed to get response\n");
//         return null;
    }

    private String httpFailure(Response response) {
        StringBuilder message = new StringBuilder("HTTP ")
                .append(response.code())
                .append(" from ")
                .append(response.request().url());
        try {
            if (response.body() != null) {
                String body = response.body().string().trim();
                if (!body.isEmpty()) {
                    message.append(": ").append(body);
                }
            }
        } catch (IOException ignored) {
            message.append(": unable to read error response body");
        }
        return message.toString();
    }

    private String safeFailureMessage(IOException failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure == null ? "unknown LLM request failure" : failure.getClass().getSimpleName();
        }
        return message.trim();
    }

    private String configuredApiKey() {
        if (config == null) {
            return Config.NO_API;
        }
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty() || Config.NO_API.equals(apiKey.trim())) {
            apiKey = config.getRandomKey();
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Config.NO_API;
        }
        return apiKey.trim();
    }
}
