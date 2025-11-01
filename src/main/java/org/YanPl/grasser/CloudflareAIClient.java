package org.YanPl.grasser;

import org.bukkit.Bukkit;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class CloudflareAIClient {

    private final String apiKey;
    private final String modelId;
    private final GrasserPlugin plugin;
    private String accountId;
    private static final String MODEL_NAME = "@cf/openai/gpt-oss-120b";

    public CloudflareAIClient(GrasserPlugin plugin, String apiKey, String modelId) {
        this.plugin = plugin;
        this.apiKey = apiKey;
        this.modelId = modelId;
        fetchAccountId().thenAccept(id -> {
            this.accountId = id;
            plugin.getLogger().info("Cloudflare Account ID fetched successfully: " + id);
        }).exceptionally(e -> {
            plugin.getLogger().severe("Failed to fetch Cloudflare Account ID: " + e.getMessage());
            this.accountId = null; // Handle error case
            return null;
        });
    }

    private CompletableFuture<String> fetchAccountId() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL("https://api.cloudflare.com/client/v4/accounts");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine = null;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        JSONObject jsonResponse = new JSONObject(response.toString());

                        if (jsonResponse.has("result") && jsonResponse.getJSONArray("result").length() > 0) {
                            String accountId = jsonResponse.getJSONArray("result").getJSONObject(0).getString("id");
                            future.complete(accountId);
                        } else {
                            plugin.getLogger().severe("Cloudflare AI API response missing 'result' field or empty when fetching account ID: " + response.toString());
                            future.completeExceptionally(new IOException("Missing 'result' field or empty in AI response when fetching account ID"));
                        }
                    }
                } else {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                        StringBuilder errorResponse = new StringBuilder();
                        String responseLine = null;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                        plugin.getLogger().severe("Failed to fetch Cloudflare Account ID: " + responseCode + " - " + errorResponse.toString());
                        future.completeExceptionally(new IOException("Unexpected code " + responseCode + " with body: " + errorResponse.toString()));
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to fetch Cloudflare Account ID: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static final String API_BASE_URL = "https://api.cloudflare.com/client/v4/accounts/";

    public CompletableFuture<String> getModifiedChat(String originalMessage, String promptPrefix, String promptSuffix) {
        CompletableFuture<String> future = new CompletableFuture<>();

        String fullPrompt = promptPrefix + originalMessage + promptSuffix + " Please ensure the response only contains standard, safe characters suitable for chat messages, avoiding any special or illegal characters that might cause issues in a game chat.";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (accountId == null) {
                    future.completeExceptionally(new IOException("Cloudflare Account ID is not available."));
                    return;
                }

                URL url = new URL(API_BASE_URL + accountId + "/ai/run/" + MODEL_NAME);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                String jsonInputString = "{\"model\": \"" + MODEL_NAME + "\", \"input\": [{\"role\": \"user\", \"content\": \"" + fullPrompt + "\"}]}";

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine = null;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        JSONObject jsonResponse = new JSONObject(response.toString());
                        if (jsonResponse.has("result")) {
                            JSONObject result = jsonResponse.getJSONObject("result");
                            if (result.has("output")) {
                                JSONArray outputArray = result.getJSONArray("output");
                                String aiResponse = null;
                                for (int i = 0; i < outputArray.length(); i++) {
                                    JSONObject outputObject = outputArray.getJSONObject(i);
                                    if (outputObject.has("type") && outputObject.getString("type").equals("message")) {
                                        if (outputObject.has("content")) {
                                            JSONArray contentArray = outputObject.getJSONArray("content");
                                            for (int j = 0; j < contentArray.length(); j++) {
                                                JSONObject contentObject = contentArray.getJSONObject(j);
                                                if (contentObject.has("type") && contentObject.getString("type").equals("output_text")) {
                                                    aiResponse = contentObject.getString("text");
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (aiResponse != null) {
                                        break;
                                    }
                                }

                                if (aiResponse != null) {
                                    future.complete(aiResponse);
                                } else {
                                    plugin.getLogger().severe("Cloudflare AI API response missing 'output_text' in content: " + response.toString());
                                    future.completeExceptionally(new IOException("Missing 'output_text' in content in AI result"));
                                }
                            } else {
                                plugin.getLogger().severe("Cloudflare AI API response missing 'output' field: " + response.toString());
                                future.completeExceptionally(new IOException("Missing 'output' field in AI result"));
                            }
                        } else {
                            plugin.getLogger().severe("Cloudflare AI API response missing 'result' field: " + response.toString());
                            future.completeExceptionally(new IOException("Missing 'result' field in AI response"));
                        }
                    }
                } else {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                        StringBuilder errorResponse = new StringBuilder();
                        String responseLine = null;
                        while ((responseLine = br.readLine()) != null) {
                            errorResponse.append(responseLine.trim());
                        }
                        plugin.getLogger().severe("Cloudflare AI API error: " + responseCode + " - " + errorResponse.toString());
                        future.completeExceptionally(new IOException("Cloudflare AI API error: " + responseCode + " - " + errorResponse.toString()));
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to connect to Cloudflare AI: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}