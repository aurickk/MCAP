package com.mcap.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcap.auth.AuthService;
import com.mcap.db.AccountRepository;
import com.mcap.minecraft.MinecraftSkinService;
import com.mcap.model.Account;
import com.mcap.pool.AccountPool;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ApiRoutes {
    private static final Logger log = LoggerFactory.getLogger(ApiRoutes.class);
    private final AccountRepository repo;
    private final AccountPool pool;
    private final AuthService authService;
    private final MinecraftSkinService skinService;
    private final Gson gson = new Gson();

    public ApiRoutes(AccountRepository repo, AccountPool pool, AuthService authService, MinecraftSkinService skinService) {
        this.repo = repo;
        this.pool = pool;
        this.authService = authService;
        this.skinService = skinService;
    }

    public void register(Javalin app) {
        app.get("/api/accounts", this::listAccounts);
        app.get("/api/accounts/{id}/head", this::accountHead);
        app.delete("/api/accounts/{id}", this::deleteAccount);
        app.post("/api/accounts/{id}/refresh", this::refreshAccount);
        app.post("/api/accounts/{id}/skin", this::changeAccountSkin);
        app.sse("/api/accounts/login", this::loginSse);
    }

    private void listAccounts(Context ctx) {
        List<Account> accounts = repo.findAll();
        List<Map<String, Object>> safe = accounts.stream().map(this::toSafeMap).toList();
        ctx.json(safe);
    }

    private void deleteAccount(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        repo.delete(id);
        ctx.json(Map.of("ok", true));
    }

    private void accountHead(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        int size = 28;
        try {
            String sizeParam = ctx.queryParam("size");
            if (sizeParam != null && !sizeParam.isBlank()) {
                size = Integer.parseInt(sizeParam);
            }
            byte[] png = skinService.renderHeadByUuid(account.getUuid(), size);
            ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            ctx.contentType("image/png");
            ctx.result(png);
        } catch (Exception e) {
            log.warn("Head render failed for account {}: {}", id, e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Head render failed"));
        }
    }

    private void refreshAccount(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            pool.refreshAccount(account);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Refresh failed: " + e.getMessage()));
        }
    }

    private void changeAccountSkin(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        if (account.getAuthJson() == null || account.getAuthJson().isBlank()) {
            ctx.status(400).json(Map.of("error", "No saved session for this account."));
            return;
        }

        String contentType = ctx.contentType();
        String variant = "classic";

        try {
            Account sessionAccount = pool.ensureValidAccessToken(account);
            byte[] png;

            if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
                UploadedFile file = ctx.uploadedFile("file");
                if (file == null) {
                    ctx.status(400).json(Map.of("error", "Missing file field."));
                    return;
                }
                String formVariant = ctx.formParam("variant");
                variant = (formVariant != null && !formVariant.isBlank()) ? formVariant : "classic";
                png = file.content().readAllBytes();
            } else {
                JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
                variant = body.has("variant") ? body.get("variant").getAsString() : "classic";
                if (body.has("url") && !body.get("url").isJsonNull()) {
                    String url = body.get("url").getAsString().trim();
                    if (url.isEmpty()) {
                        ctx.status(400).json(Map.of("error", "URL is empty."));
                        return;
                    }
                    png = skinService.fetchUrl(url);
                } else if (body.has("username") && !body.get("username").isJsonNull()) {
                    String username = body.get("username").getAsString().trim();
                    if (username.isEmpty()) {
                        ctx.status(400).json(Map.of("error", "Username is empty."));
                        return;
                    }
                    png = skinService.fetchSkinByUsername(username);
                } else {
                    ctx.status(400).json(Map.of("error", "Provide a skin URL or username in JSON, or upload multipart file."));
                    return;
                }
            }

            skinService.uploadSkinPng(sessionAccount.getAccessToken(), png, variant);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            log.warn("Skin change failed: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Skin change failed"));
        }
    }

    private void loginSse(SseClient client) {
        client.keepAlive();
        client.sendEvent("status", "Starting Microsoft login...");

        Thread.ofVirtual().start(() -> {
            try {
                AuthService.LoginResult result = authService.login(deviceCode -> {
                    JsonObject codeData = new JsonObject();
                    codeData.addProperty("userCode", deviceCode.getUserCode());
                    codeData.addProperty("verificationUri", deviceCode.getDirectVerificationUri());
                    client.sendEvent("device_code", gson.toJson(codeData));
                });

                Account account = new Account();
                account.setUuid(result.uuid());
                account.setUsername(result.username());
                account.setAuthJson(result.authJson());
                account.setAccessToken(result.accessToken());
                account.setRefreshToken(result.refreshToken());
                account.setTokenExpiry(result.tokenExpiry());
                repo.save(account);

                JsonObject successData = new JsonObject();
                successData.addProperty("username", result.username());
                successData.addProperty("uuid", result.uuid());
                client.sendEvent("success", gson.toJson(successData));
            } catch (Exception e) {
                log.error("Login failed", e);
                client.sendEvent("error", e.getMessage());
            } finally {
                client.close();
            }
        });
    }

    private Map<String, Object> toSafeMap(Account a) {
        return Map.ofEntries(
            Map.entry("id", a.getId()),
            Map.entry("uuid", a.getUuid()),
            Map.entry("username", a.getUsername()),
            Map.entry("accessToken", a.getAccessToken() != null ? a.getAccessToken() : ""),
            Map.entry("refreshToken", a.getRefreshToken() != null ? a.getRefreshToken() : ""),
            Map.entry("tokenExpiry", a.getTokenExpiry()),
            Map.entry("createdAt", a.getCreatedAt()),
            Map.entry("updatedAt", a.getUpdatedAt())
        );
    }
}
