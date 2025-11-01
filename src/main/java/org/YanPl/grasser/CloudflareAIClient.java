package org.YanPl.grasser;

import okhttp3.*;
import org.bukkit.Bukkit;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class CloudflareAIClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final String accountId;
    private final String apiKey;
    private final String modelId;
    private final GrasserPlugin plugin;

    public CloudflareAIClient(GrasserPlugin plugin, String accountId, String apiKey, String modelId) {
        this.plugin = plugin;
        this.client = new OkHttpClient();
        this.accountId = accountId;
        this.apiKey = apiKey;
        this.modelId = modelId;
    }

    public CompletableFuture<String> getModifiedChat(String originalMessage, String promptPrefix, String promptSuffix) {
        CompletableFuture<String> future = new CompletableFuture<>();

        String fullPrompt = promptPrefix + originalMessage + promptSuffix;

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", fullPrompt);

        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("messages", messages);

        RequestBody body = RequestBody.create(jsonBody.toString(), JSON);

        String url = String.format("https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s", accountId, modelId);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                plugin.getLogger().severe("Failed to connect to Cloudflare AI: " + e.getMessage());
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        String errorBody = responseBody != null ? responseBody.string() : "No response body";
                        plugin.getLogger().severe("Cloudflare AI API error: " + response.code() + " - " + errorBody);
                        future.completeExceptionally(new IOException("Unexpected code " + response + " with body: " + errorBody));
                        return;
                    }

                    if (responseBody == null) {
                        plugin.getLogger().severe("Cloudflare AI API returned empty response body.");
                        future.completeExceptionally(new IOException("Empty response body"));
                        return;
                    }

                    String responseString = responseBody.string();
                    JSONObject jsonResponse = new JSONObject(responseString);

                    if (jsonResponse.has("result")) {
                        JSONObject result = jsonResponse.getJSONObject("result");
                        if (result.has("response")) {
                            String aiResponse = result.getString("response");
                            future.complete(aiResponse);
                        } else {
                            plugin.getLogger().severe("Cloudflare AI API response missing 'response' field: " + responseString);
                            future.completeExceptionally(new IOException("Missing 'response' field in AI result"));
                        }
                    } else {
                        plugin.getLogger().severe("Cloudflare AI API response missing 'result' field: " + responseString);
                        future.completeExceptionally(new IOException("Missing 'result' field in AI response"));
                    }
                }
            }
        });

        return future;
    }
}