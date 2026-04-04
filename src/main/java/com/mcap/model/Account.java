package com.mcap.model;

public class Account {
    private int id;
    private String uuid;
    private String username;
    private String authJson;
    private String accessToken;
    private String refreshToken;
    private long tokenExpiry;
    private long createdAt;
    private long updatedAt;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAuthJson() { return authJson; }
    public void setAuthJson(String authJson) { this.authJson = authJson; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public long getTokenExpiry() { return tokenExpiry; }
    public void setTokenExpiry(long tokenExpiry) { this.tokenExpiry = tokenExpiry; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
