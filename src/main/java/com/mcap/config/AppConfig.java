package com.mcap.config;

public class AppConfig {
    private int port = 7070;
    private String dbPath = "mcap.db";
    private int refreshIntervalMinutes = 30;

    public int getPort() { return port; }
    public String getDbPath() { return dbPath; }
    public int getRefreshIntervalMinutes() { return refreshIntervalMinutes; }

    public static AppConfig fromEnv() {
        AppConfig config = new AppConfig();
        String port = System.getenv("MCAP_PORT");
        if (port != null) config.port = Integer.parseInt(port);
        String db = System.getenv("MCAP_DB");
        if (db != null) config.dbPath = db;
        String refresh = System.getenv("MCAP_REFRESH_MINUTES");
        if (refresh != null) config.refreshIntervalMinutes = Integer.parseInt(refresh);
        return config;
    }
}
