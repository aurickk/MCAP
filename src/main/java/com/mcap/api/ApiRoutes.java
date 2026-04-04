package com.mcap.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mcap.auth.AuthService;
import com.mcap.db.AccountRepository;
import com.mcap.model.Account;
import com.mcap.pool.AccountPool;
import io.javalin.Javalin;
import io.javalin.http.Context;
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
    private final Gson gson = new Gson();

    public ApiRoutes(AccountRepository repo, AccountPool pool, AuthService authService) {
        this.repo = repo;
        this.pool = pool;
        this.authService = authService;
    }

    public void register(Javalin app) {
        app.get("/api/accounts", this::listAccounts);
        app.delete("/api/accounts/{id}", this::deleteAccount);
        app.post("/api/accounts/{id}/refresh", this::refreshAccount);
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
