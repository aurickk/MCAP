package com.mcap.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcap.auth.AuthService;
import com.mcap.db.AccountRepository;
import com.mcap.minecraft.MinecraftProfileService;
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
    private final MinecraftProfileService profileService;
    private final Gson gson = new Gson();

    public ApiRoutes(AccountRepository repo, AccountPool pool, AuthService authService, MinecraftProfileService profileService) {
        this.repo = repo;
        this.pool = pool;
        this.authService = authService;
        this.profileService = profileService;
    }

    public void register(Javalin app) {
        app.get("/api/version", ctx -> ctx.json(Map.of("version", getVersion())));
        app.get("/api/accounts", this::listAccounts);
        app.delete("/api/accounts/{id}", this::deleteAccount);
        app.post("/api/accounts/{id}/refresh", this::refreshAccount);
        app.sse("/api/accounts/login", this::loginSse);
        app.post("/api/accounts/login/token", this::loginWithToken);
        app.get("/api/accounts/{id}/profile", this::getProfile);
        app.get("/api/accounts/{id}/skin-image", this::getSkinImage);
        app.get("/api/accounts/{id}/cape-image", this::getCapeImage);
        app.post("/api/accounts/{id}/skin", this::uploadSkin);
        app.put("/api/accounts/{id}/cape", this::equipCape);
        app.delete("/api/accounts/{id}/cape", this::hideCape);
        app.get("/api/accounts/name/{name}/available", this::checkNameAvailability);
        app.put("/api/accounts/{id}/name", this::changeName);
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

    private void loginWithToken(Context ctx) {
        try {
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String refreshToken = body.has("refreshToken") ? body.get("refreshToken").getAsString() : null;
            String accessToken = body.has("accessToken") ? body.get("accessToken").getAsString() : null;

            if (refreshToken == null || refreshToken.isBlank()) {
                ctx.status(400).json(Map.of("error", "Refresh token is required"));
                return;
            }
            refreshToken = refreshToken.trim();

            // If a session token is provided and still valid, use it directly
            if (accessToken != null && !accessToken.isBlank()) {
                accessToken = accessToken.trim();
                long expiry = AuthService.parseJwtExpiry(accessToken);
                if (expiry > System.currentTimeMillis()) {
                    try {
                        MinecraftProfileService.ProfileData profile = profileService.fetchProfile(accessToken);
                        String uuid = profile.uuid();
                        // Mojang returns UUID without dashes — normalize to dashed format
                        if (uuid != null && !uuid.contains("-") && uuid.length() == 32) {
                            uuid = uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                        }
                        Account account = new Account();
                        account.setUuid(uuid);
                        account.setUsername(profile.username());
                        account.setAuthJson("");
                        account.setAccessToken(accessToken);
                        account.setRefreshToken(refreshToken);
                        account.setTokenExpiry(expiry);
                        repo.save(account);
                        log.info("Imported {} with existing session token (expires in {}m)",
                            profile.username(), (expiry - System.currentTimeMillis()) / 60000);
                        ctx.json(Map.of("ok", true, "username", profile.username(), "uuid", uuid));
                        return;
                    } catch (Exception e) {
                        log.info("Session token rejected by Mojang, falling back to refresh token auth: {}", e.getMessage());
                    }
                } else {
                    log.info("Provided session token is expired, falling back to refresh token auth");
                }
            }

            // Full auth from refresh token
            AuthService.LoginResult result = authService.loginWithRefreshToken(refreshToken);

            Account account = new Account();
            account.setUuid(result.uuid());
            account.setUsername(result.username());
            account.setAuthJson(result.authJson());
            account.setAccessToken(result.accessToken());
            account.setRefreshToken(result.refreshToken());
            account.setTokenExpiry(result.tokenExpiry());
            repo.save(account);

            ctx.json(Map.of("ok", true, "username", result.username(), "uuid", result.uuid()));
        } catch (Exception e) {
            log.error("Token import failed", e);
            ctx.status(500).json(Map.of("error", "Login failed: " + e.getMessage()));
        }
    }

    private void getProfile(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            account = pool.ensureValidAccessToken(account);
            MinecraftProfileService.ProfileData profile = profileService.fetchProfile(account.getAccessToken());
            // Sync username if changed externally
            if (profile.username() != null && !profile.username().equals(account.getUsername())) {
                repo.updateUsername(id, profile.username());
            }
            ctx.json(Map.of(
                "skinUrl", profile.skinUrl() != null ? profile.skinUrl() : "",
                "skinModel", profile.skinModel(),
                "capes", profile.capes()
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to fetch profile: " + e.getMessage()));
        }
    }

    private void getSkinImage(Context ctx) {
        proxyTextureImage(ctx, "skin");
    }

    private void getCapeImage(Context ctx) {
        proxyTextureImage(ctx, "cape");
    }

    private void proxyTextureImage(Context ctx, String type) {
        String url = ctx.queryParam("url");
        if (url == null || url.isEmpty()) {
            ctx.status(400).result("Missing url parameter");
            return;
        }
        try {
            byte[] image = profileService.fetchTextureImage(url);
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.contentType("image/png").result(image);
        } catch (IllegalArgumentException e) {
            ctx.status(400).result(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to fetch {} image: {}", type, e.getMessage());
            ctx.status(500).result("Failed to fetch " + type + ": " + e.getMessage());
        }
    }

    private void uploadSkin(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            account = pool.ensureValidAccessToken(account);
            var uploadedFile = ctx.uploadedFile("file");
            if (uploadedFile == null) {
                ctx.status(400).json(Map.of("error", "No skin file provided"));
                return;
            }
            byte[] pngBytes = uploadedFile.content().readAllBytes();
            String variant = ctx.formParam("variant");
            profileService.uploadSkin(account.getAccessToken(), pngBytes, variant);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Skin upload failed: " + e.getMessage()));
        }
    }

    private void equipCape(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            account = pool.ensureValidAccessToken(account);
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String capeId = body.get("capeId").getAsString();
            profileService.equipCape(account.getAccessToken(), capeId);
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to equip cape: " + e.getMessage()));
        }
    }

    private void hideCape(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            account = pool.ensureValidAccessToken(account);
            profileService.hideCape(account.getAccessToken());
            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to hide cape: " + e.getMessage()));
        }
    }

    private void checkNameAvailability(Context ctx) {
        String name = ctx.pathParam("name");
        try {
            // Need a valid access token from any account for this API call
            List<Account> allAccounts = repo.findAll();
            if (allAccounts.isEmpty()) {
                ctx.status(400).json(Map.of("error", "No accounts available for API authentication"));
                return;
            }
            Account account = pool.ensureValidAccessToken(allAccounts.getFirst());
            MinecraftProfileService.NameAvailability result = profileService.checkNameAvailability(account.getAccessToken(), name);
            ctx.json(Map.of("status", result.status()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Name check failed: " + e.getMessage()));
        }
    }

    private void changeName(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        Account account = repo.findById(id);
        if (account == null) {
            ctx.status(404).json(Map.of("error", "Account not found"));
            return;
        }
        try {
            account = pool.ensureValidAccessToken(account);
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String newName = body.get("name").getAsString();
            profileService.changeName(account.getAccessToken(), newName);
            repo.updateUsername(id, newName);
            ctx.json(Map.of("ok", true, "username", newName));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Name change failed: " + e.getMessage()));
        }
    }

    private String getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version != null ? version : "dev";
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
