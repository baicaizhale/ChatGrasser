package org.YanPl.grasser;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GrasserPlugin extends JavaPlugin {

    private String cloudflareApiKey;
    private String cloudflareModelId;
    private boolean chatModifierEnabled;
    private String chatModifierPromptPrefix;
    private String chatModifierPromptSuffix;
    private CloudflareAIClient aiClient;

    @Override
    public void onEnable() {
        // 插件启用逻辑
        getLogger().info("GrasserPlugin has been enabled!");

        saveDefaultConfig(); // 保存默认配置
        FileConfiguration config = getConfig();

        cloudflareApiKey = config.getString("cloudflare.api_key");
        cloudflareModelId = config.getString("cloudflare.model_id");
        chatModifierEnabled = config.getBoolean("chat_modifier.enabled");
        chatModifierPromptPrefix = config.getString("chat_modifier.prompt_prefix");
        chatModifierPromptSuffix = config.getString("chat_modifier.prompt_suffix");

        this.aiClient = new CloudflareAIClient(this, cloudflareApiKey, cloudflareModelId);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
    }

    @Override
    public void onDisable() {
        // 插件禁用逻辑
        getLogger().info("GrasserPlugin has been disabled!");
    }

    public String getCloudflareApiKey() {
        return cloudflareApiKey;
    }

    public String getCloudflareModelId() {
        return cloudflareModelId;
    }

    public boolean isChatModifierEnabled() {
        return chatModifierEnabled;
    }

    public String getChatModifierPromptPrefix() {
        return chatModifierPromptPrefix;
    }

    public String getChatModifierPromptSuffix() {
        return chatModifierPromptSuffix;
    }

    public CloudflareAIClient getAiClient() {
        return aiClient;
    }
}

class ChatListener implements Listener {

    private final GrasserPlugin plugin;
    private final Set<UUID> bypassChatModifier = new HashSet<>();

    public ChatListener(GrasserPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (bypassChatModifier.contains(event.getPlayer().getUniqueId())) {
            bypassChatModifier.remove(event.getPlayer().getUniqueId());
            return; // Bypass modification for this message
        }

        if (!plugin.isChatModifierEnabled()) {
            return;
        }

        event.setCancelled(true); // 取消原始聊天事件
        String originalMessage = event.getMessage();
        // getLogger().info("Intercepted chat from " + event.getPlayer().getName() + ": " + originalMessage);

        CompletableFuture<String> futureModifiedMessage = plugin.getAiClient().getModifiedChat(
                originalMessage,
                plugin.getChatModifierPromptPrefix(),
                plugin.getChatModifierPromptSuffix()
        );

        futureModifiedMessage.whenComplete((modifiedMessage, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().severe("Error getting modified chat from AI: " + throwable.getMessage());
                event.getPlayer().sendMessage("§cAI 聊天修改失败: " + throwable.getMessage());
                return;
            }
            // 将修改后的消息发送到聊天框
            Bukkit.getScheduler().runTask(plugin, () -> {
                bypassChatModifier.add(event.getPlayer().getUniqueId());
                event.getPlayer().chat(modifiedMessage); // 使用 chat 方法以确保消息格式正确
            });
        });
    }
}