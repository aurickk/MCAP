package com.mcap.db;

import com.mcap.model.Account;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class AccountRepository {
    private final String dbUrl;

    public AccountRepository(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initTable();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid           TEXT NOT NULL UNIQUE,
                    username       TEXT NOT NULL,
                    auth_json      TEXT NOT NULL,
                    access_token   TEXT,
                    refresh_token  TEXT,
                    token_expiry   INTEGER,
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    public void save(Account account) {
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO accounts (uuid, username, auth_json, access_token, refresh_token, token_expiry, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                auth_json = excluded.auth_json,
                access_token = excluded.access_token,
                refresh_token = excluded.refresh_token,
                token_expiry = excluded.token_expiry,
                updated_at = ?
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, account.getUuid());
            ps.setString(2, account.getUsername());
            ps.setString(3, account.getAuthJson());
            ps.setString(4, account.getAccessToken());
            ps.setString(5, account.getRefreshToken());
            ps.setLong(6, account.getTokenExpiry());
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save account", e);
        }
    }

    public List<Account> findAll() {
        List<Account> accounts = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM accounts ORDER BY created_at DESC")) {
            while (rs.next()) {
                accounts.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch accounts", e);
        }
        return accounts;
    }

    public Account findById(int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch account", e);
        }
        return null;
    }

    public void updateTokens(int id, String authJson, String accessToken, String refreshToken, long tokenExpiry) {
        long now = System.currentTimeMillis();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE accounts SET auth_json = ?, access_token = ?, refresh_token = ?, token_expiry = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, authJson);
            ps.setString(2, accessToken);
            ps.setString(3, refreshToken);
            ps.setLong(4, tokenExpiry);
            ps.setLong(5, now);
            ps.setInt(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update tokens", e);
        }
    }

    public void setError(int id) {
        long now = System.currentTimeMillis();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE accounts SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update account", e);
        }
    }

    public void delete(int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete account", e);
        }
    }

    private Account mapRow(ResultSet rs) throws SQLException {
        Account a = new Account();
        a.setId(rs.getInt("id"));
        a.setUuid(rs.getString("uuid"));
        a.setUsername(rs.getString("username"));
        a.setAuthJson(rs.getString("auth_json"));
        a.setAccessToken(rs.getString("access_token"));
        a.setRefreshToken(rs.getString("refresh_token"));
        a.setTokenExpiry(rs.getLong("token_expiry"));
        a.setCreatedAt(rs.getLong("created_at"));
        a.setUpdatedAt(rs.getLong("updated_at"));
        return a;
    }
}
