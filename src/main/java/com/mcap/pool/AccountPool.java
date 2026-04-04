package com.mcap.pool;

import com.mcap.auth.AuthService;
import com.mcap.db.AccountRepository;
import com.mcap.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AccountPool {
    private static final Logger log = LoggerFactory.getLogger(AccountPool.class);
    private final AccountRepository repo;
    private final AuthService authService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-refresh");
        t.setDaemon(true);
        return t;
    });

    public AccountPool(AccountRepository repo, AuthService authService) {
        this.repo = repo;
        this.authService = authService;
    }

    public void startRefreshScheduler(int intervalMinutes) {
        scheduler.scheduleAtFixedRate(this::refreshAll, 1, intervalMinutes, TimeUnit.MINUTES);
        log.info("Token refresh scheduler started (every {} minutes)", intervalMinutes);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private void refreshAll() {
        log.info("Running token refresh cycle...");
        for (Account account : repo.findAll()) {
            if (!authService.isExpired(account.getTokenExpiry())) {
                continue;
            }
            try {
                refreshAccount(account);
            } catch (Exception e) {
                // Already logged and error state set in refreshAccount
            }
        }
    }

    public Account ensureValidAccessToken(Account account) throws Exception {
        if (authService.isExpired(account.getTokenExpiry())) {
            refreshAccount(account);
            return repo.findById(account.getId());
        }
        return account;
    }

    public void refreshAccount(Account account) throws Exception {
        try {
            log.info("Refreshing token for {} ({})", account.getUsername(), account.getUuid());
            AuthService.LoginResult result = authService.refresh(account.getAuthJson());
            repo.updateTokens(account.getId(), result.authJson(), result.accessToken(), result.refreshToken(), result.tokenExpiry());
            if (result.username() != null && !result.username().equals(account.getUsername())) {
                repo.updateUsername(account.getId(), result.username());
                log.info("Username updated: {} -> {}", account.getUsername(), result.username());
            }
            log.info("Successfully refreshed token for {}", result.username());
        } catch (Exception e) {
            log.error("Failed to refresh token for {} ({}): {}", account.getUsername(), account.getUuid(), e.getMessage());
            repo.setError(account.getId());
            throw e;
        }
    }
}
