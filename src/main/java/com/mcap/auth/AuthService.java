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
}
