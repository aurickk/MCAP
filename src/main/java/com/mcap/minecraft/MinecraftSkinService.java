package com.mcap.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Uploads a skin PNG to Mojang's Minecraft Services API using a Minecraft access token.
 */
public class MinecraftSkinService {
    private static final String SKINS_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile/skins";
    private static final String SESSION_PROFILE_ENDPOINT = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final int MAX_SKIN_BYTES = 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public void uploadSkinPng(String minecraftAccessToken, byte[] pngBytes, String variant) throws Exception {
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
            .header("Authorization", "Bearer " + minecraftAccessToken)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            return;
        }
        throw new Exception(parseMojangError(res.body(), res.statusCode()));
    }

    public byte[] fetchUrl(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .GET()
            .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception("Could not download image (HTTP " + res.statusCode() + ").");
        }
        byte[] body = res.body();
        if (body == null || body.length < 64) {
            throw new Exception("Downloaded image is too small.");
        }
        if (body.length > MAX_SKIN_BYTES) {
            throw new Exception("Downloaded image is too large.");
        }
        return body;
    }

    public byte[] fetchSkinByUsername(String username) throws Exception {
        String enc = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8);
        String[] urls = {
            "https://minotar.net/skin/" + enc,
            "https://mc-heads.net/skin/" + enc,
        };
        Exception last = null;
        for (String u : urls) {
            try {
                byte[] bytes = fetchUrl(u);
                if (isPng(bytes)) {
                    return bytes;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception(last != null ? last.getMessage() : "Could not find a skin for that username.");
    }

    public byte[] renderHeadByUuid(String uuid, int size) throws Exception {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("UUID is missing.");
        }
        int safeSize = Math.max(8, Math.min(256, size));
        String textureUrl = fetchSkinTextureUrlByUuid(uuid);
        byte[] skinPng = fetchUrl(textureUrl);
        return renderHeadPng(skinPng, safeSize);
    }

    private String fetchSkinTextureUrlByUuid(String uuid) throws Exception {
        String normalized = uuid.replace("-", "").trim();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(SESSION_PROFILE_ENDPOINT + normalized))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new Exception("Could not resolve skin profile (HTTP " + res.statusCode() + ").");
        }
        JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!profile.has("properties") || !profile.get("properties").isJsonArray()) {
            throw new Exception("Skin profile did not include textures.");
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
            if (!textures.has("SKIN")) continue;
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (!skin.has("url") || skin.get("url").isJsonNull()) continue;
            return skin.get("url").getAsString();
        }
        throw new Exception("Skin texture URL not found for this profile.");
    }

    private static byte[] renderHeadPng(byte[] skinPng, int size) throws Exception {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        if (skin == null) {
            throw new Exception("Could not decode skin image.");
        }
        int w = skin.getWidth();
        int h = skin.getHeight();
        BufferedImage head = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = head.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

            if ((w >= 64 && h >= 64) || (w == 64 && h == 32)) {
                g.drawImage(skin, 0, 0, size, size, 8, 8, 16, 16, null);
                g.drawImage(skin, 0, 0, size, size, 40, 8, 48, 16, null);
            } else if (w == 32 && h == 32) {
                g.drawImage(skin, 0, 0, size, size, 8, 8, 16, 16, null);
                g.drawImage(skin, 0, 0, size, size, 24, 8, 32, 16, null);
            } else {
                g.drawImage(skin, 0, 0, size, size, 0, 0, w, h, null);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(size * size);
        ImageIO.write(head, "png", out);
        return out.toByteArray();
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
            return "Skin upload failed (HTTP " + status + ").";
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
        } catch (Exception ignored) {
            /* fall through */
        }
        return "Skin upload failed (HTTP " + status + ").";
    }
}
