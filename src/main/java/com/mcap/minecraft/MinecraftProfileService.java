package com.mcap.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class MinecraftProfileService {
    private static final String PROFILE_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SKINS_ENDPOINT = PROFILE_ENDPOINT + "/skins";
    private static final String CAPES_ENDPOINT = PROFILE_ENDPOINT + "/capes/active";
    private static final String SESSION_PROFILE_ENDPOINT = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String NAME_ENDPOINT = PROFILE_ENDPOINT + "/name/";
    private static final int MAX_SKIN_BYTES = 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public record ProfileTextures(String skinUrl, String skinModel, String capeUrl) {}
    public record Cape(String id, String state, String url, String alias) {}
    public record ProfileData(String username, String skinUrl, String skinModel, List<Cape> capes) {}
    public record NameAvailability(String status) {}

    public ProfileTextures fetchProfileTextures(String uuid) throws Exception {
        String normalized = uuid.replace("-", "").trim();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(SESSION_PROFILE_ENDPOINT + normalized))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception("Could not resolve profile (HTTP " + res.statusCode() + ").");
        }

        JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!profile.has("properties") || !profile.get("properties").isJsonArray()) {
            throw new Exception("Profile did not include textures.");
        }

        for (var propEl : profile.getAsJsonArray("properties")) {
            if (!propEl.isJsonObject()) continue;
            JsonObject prop = propEl.getAsJsonObject();
            if (!prop.has("name") || !prop.has("value")) continue;
            if (!"textures".equals(prop.get("name").getAsString())) continue;

            String encoded = prop.get("value").getAsString();
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject texturesObj = JsonParser.parseString(decoded).getAsJsonObject();
            if (!texturesObj.has("textures")) continue;
            JsonObject textures = texturesObj.getAsJsonObject("textures");

            String skinUrl = null;
            String skinModel = "classic";
            String capeUrl = null;

            if (textures.has("SKIN") && textures.get("SKIN").isJsonObject()) {
                JsonObject skin = textures.getAsJsonObject("SKIN");
                if (skin.has("url") && !skin.get("url").isJsonNull()) {
                    skinUrl = skin.get("url").getAsString();
                }
                if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                    JsonObject meta = skin.getAsJsonObject("metadata");
                    if (meta.has("model") && "slim".equals(meta.get("model").getAsString())) {
                        skinModel = "slim";
                    }
                }
            }

            if (textures.has("CAPE") && textures.get("CAPE").isJsonObject()) {
                JsonObject cape = textures.getAsJsonObject("CAPE");
                if (cape.has("url") && !cape.get("url").isJsonNull()) {
                    capeUrl = cape.get("url").getAsString();
                }
            }

            return new ProfileTextures(skinUrl, skinModel, capeUrl);
        }

        return new ProfileTextures(null, "classic", null);
    }

    public void uploadSkin(String accessToken, byte[] pngBytes, String variant) throws Exception {
        if (pngBytes == null || pngBytes.length < 64) {
            throw new IllegalArgumentException("Skin file is too small or empty.");
        }
        if (pngBytes.length > MAX_SKIN_BYTES) {
            throw new IllegalArgumentException("Skin file is too large (max 1 MB).");
        }
        if (!isPng(pngBytes)) {
            throw new IllegalArgumentException("Skin must be a valid PNG file.");
        }
        String model = "slim".equalsIgnoreCase(variant != null ? variant.trim() : "") ? "slim" : "classic";
        String boundary = "mcap-" + UUID.randomUUID();
        byte[] body = buildMultipart(boundary, model, pngBytes);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(SKINS_ENDPOINT))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            return;
        }
        throw new Exception(parseMojangError(res.body(), res.statusCode()));
    }

    public NameAvailability checkNameAvailability(String accessToken, String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(NAME_ENDPOINT + name + "/available"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception(parseMojangError(res.body(), res.statusCode()));
        }
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        String status = json.has("status") ? json.get("status").getAsString() : "UNKNOWN";
        return new NameAvailability(status);
    }

    public void changeName(String accessToken, String newName) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(NAME_ENDPOINT + newName))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            return;
        }
        throw new Exception(parseMojangError(res.body(), res.statusCode()));
    }

    public ProfileData fetchProfile(String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(PROFILE_ENDPOINT))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception(parseMojangError(res.body(), res.statusCode()));
        }
        JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
        String username = profile.has("name") ? profile.get("name").getAsString() : null;

        // Parse skins
        String skinUrl = null;
        String skinModel = "classic";
        if (profile.has("skins") && profile.get("skins").isJsonArray()) {
            for (var el : profile.getAsJsonArray("skins")) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                if (s.has("state") && "ACTIVE".equals(s.get("state").getAsString())) {
                    skinUrl = s.has("url") ? s.get("url").getAsString() : null;
                    if (s.has("variant") && "SLIM".equalsIgnoreCase(s.get("variant").getAsString())) {
                        skinModel = "slim";
                    }
                    break;
                }
            }
        }

        // Parse capes
        List<Cape> capes = new ArrayList<>();
        if (profile.has("capes") && profile.get("capes").isJsonArray()) {
            for (var el : profile.getAsJsonArray("capes")) {
                if (!el.isJsonObject()) continue;
                JsonObject c = el.getAsJsonObject();
                capes.add(new Cape(
                    c.has("id") ? c.get("id").getAsString() : "",
                    c.has("state") ? c.get("state").getAsString() : "INACTIVE",
                    c.has("url") ? c.get("url").getAsString() : "",
                    c.has("alias") ? c.get("alias").getAsString() : ""
                ));
            }
        }

        return new ProfileData(username, skinUrl, skinModel, capes);
    }

    public void equipCape(String accessToken, String capeId) throws Exception {
        String body = "{\"capeId\":\"" + capeId + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(CAPES_ENDPOINT))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception(parseMojangError(res.body(), res.statusCode()));
        }
    }

    public void hideCape(String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(CAPES_ENDPOINT))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .DELETE()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception(parseMojangError(res.body(), res.statusCode()));
        }
    }

    public byte[] fetchTextureImage(String url) throws Exception {
        if (url == null || (!url.startsWith("https://textures.minecraft.net/") && !url.startsWith("http://textures.minecraft.net/"))) {
            throw new IllegalArgumentException("Invalid texture URL.");
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception("Failed to fetch texture (HTTP " + res.statusCode() + ").");
        }
        return res.body();
    }

    private static byte[] buildMultipart(String boundary, String variant, byte[] pngBytes) throws Exception {
        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(pngBytes.length + 512);
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"variant\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(variant.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: image/png" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(pngBytes);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static boolean isPng(byte[] data) {
        if (data == null || data.length < 8) return false;
        return data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
            && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A;
    }

    private static String parseMojangError(String body, int status) {
        if (body == null || body.isBlank()) {
            return "Request failed (HTTP " + status + ").";
        }
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("errorMessage") && !o.get("errorMessage").isJsonNull()) {
                return o.get("errorMessage").getAsString();
            }
            if (o.has("developerMessage") && !o.get("developerMessage").isJsonNull()) {
                return o.get("developerMessage").getAsString();
            }
            if (o.has("error") && !o.get("error").isJsonNull()) {
                return o.get("error").getAsString();
            }
        } catch (Exception ignored) {}
        return "Request failed (HTTP " + status + ").";
    }
}
