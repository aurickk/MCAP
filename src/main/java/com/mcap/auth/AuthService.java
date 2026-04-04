package com.mcap.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.function.Consumer;

public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record LoginResult(
        String uuid,
        String username,
        String accessToken,
        String refreshToken,
        long tokenExpiry,
        String authJson
    ) {}

    public LoginResult login(Consumer<MsaDeviceCode> deviceCodeCallback) throws Exception {
        JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
            .login(DeviceCodeMsaAuthService::new, deviceCodeCallback);

        return extractResult(authManager);
    }

    public LoginResult loginWithRefreshToken(String refreshToken) throws Exception {
        return retryOnRateLimit(() -> {
            JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login(refreshToken);
            return extractResult(authManager);
        });
    }

    private LoginResult retryOnRateLimit(AuthAction action) throws Exception {
        int maxRetries = 5;
        long delay = 10_000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.execute();
            } catch (Exception e) {
                if (attempt < maxRetries && e.getMessage() != null && e.getMessage().contains("429")) {
                    log.warn("Rate limited, retrying in {}s (attempt {}/{})", delay / 1000, attempt + 1, maxRetries);
                    Thread.sleep(delay);
                    delay *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("Unreachable");
    }

    @FunctionalInterface
    private interface AuthAction {
        LoginResult execute() throws Exception;
    }

    public LoginResult refresh(String authJson) throws Exception {
        JsonObject json = JsonParser.parseString(authJson).getAsJsonObject();
        JavaAuthManager authManager = JavaAuthManager.fromJson(MinecraftAuth.createHttpClient(), json);

        // getUpToDate() auto-refreshes expired tokens
        authManager.getMinecraftToken().getUpToDate();
        authManager.getMinecraftProfile().getUpToDate();

        return extractResult(authManager);
    }

    private LoginResult extractResult(JavaAuthManager authManager) throws Exception {
        MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
        MinecraftToken mcToken = authManager.getMinecraftToken().getUpToDate();

        String uuid = profile.getId().toString();
        String username = profile.getName();
        String accessToken = mcToken.getToken();
        long expiry = mcToken.getExpireTimeMs();

        log.info("Token for {}: expires in {}m", username,
            (expiry - System.currentTimeMillis()) / 60000);

        String refreshToken = authManager.getMsaToken().getCached().getRefreshToken();

        JsonObject json = JavaAuthManager.toJson(authManager);
        return new LoginResult(uuid, username, accessToken, refreshToken, expiry, json.toString());
    }

    public boolean isExpired(long tokenExpiry) {
        return System.currentTimeMillis() >= tokenExpiry;
    }

    /**
     * Decode a Minecraft session token (JWT) and extract the expiry time.
     * Returns 0 if the token cannot be parsed.
     */
    public static long parseJwtExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            if (json.has("exp")) {
                return json.get("exp").getAsLong() * 1000; // JWT exp is in seconds
            }
        } catch (Exception e) {
            log.warn("Failed to parse JWT expiry: {}", e.getMessage());
        }
        return 0;
    }
}
