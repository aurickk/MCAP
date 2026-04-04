package com.mcap.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

    public LoginResult login(Consumer<StepMsaDeviceCode.MsaDeviceCode> deviceCodeCallback) throws Exception {
        StepFullJavaSession.FullJavaSession session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.getFromInput(
            MinecraftAuth.createHttpClient(),
            new StepMsaDeviceCode.MsaDeviceCodeCallback(deviceCodeCallback)
        );

        return extractResult(session);
    }

    public LoginResult refresh(String authJson) throws Exception {
        JsonObject json = JsonParser.parseString(authJson).getAsJsonObject();

        // Recursively zero out every expireTimeMs in the entire JSON tree.
        // This forces MinecraftAuth to treat all steps as expired and re-fetch them.
        zeroAllExpiry(json);

        log.info("Forced all tokens expired, starting refresh...");

        StepFullJavaSession.FullJavaSession session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.fromJson(json);
        StepFullJavaSession.FullJavaSession refreshed = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.refresh(
            MinecraftAuth.createHttpClient(),
            session
        );

        return extractResult(refreshed);
    }

    private void zeroAllExpiry(JsonObject json) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getKey().equals("expireTimeMs")) {
                json.addProperty("expireTimeMs", 0);
            } else if (entry.getValue().isJsonObject()) {
                zeroAllExpiry(entry.getValue().getAsJsonObject());
            }
        }
    }

    private LoginResult extractResult(StepFullJavaSession.FullJavaSession session) {
        JsonObject json = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.toJson(session);

        String uuid = session.getMcProfile().getId().toString();
        String username = session.getMcProfile().getName();
        String accessToken = session.getMcProfile().getMcToken().getAccessToken();
        long expiry = session.getMcProfile().getMcToken().getExpireTimeMs();

        log.info("Token for {}: expires in {}m", username,
            (expiry - System.currentTimeMillis()) / 60000);

        String refreshToken = session.getMcProfile()
            .getMcToken()
            .getXblXsts()
            .getInitialXblSession()
            .getMsaToken()
            .getRefreshToken();

        return new LoginResult(uuid, username, accessToken, refreshToken, expiry, json.toString());
    }

    public boolean isExpired(long tokenExpiry) {
        return System.currentTimeMillis() >= tokenExpiry;
    }
}
