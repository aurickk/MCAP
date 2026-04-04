package com.mcap;

import com.mcap.api.ApiRoutes;
import com.mcap.auth.AuthService;
import com.mcap.config.AppConfig;
import com.mcap.db.AccountRepository;
import com.mcap.pool.AccountPool;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();

        AccountRepository repo = new AccountRepository(config.getDbPath());
        AuthService authService = new AuthService();
        AccountPool pool = new AccountPool(repo, authService);

        ApiRoutes routes = new ApiRoutes(repo, pool, authService);

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.staticFiles.add("/static");
            javalinConfig.http.asyncTimeout = 300_000L; // 5 min for device code flow
        });

        routes.register(app);

        pool.startRefreshScheduler(config.getRefreshIntervalMinutes());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            pool.shutdown();
            app.stop();
        }));

        app.start(config.getPort());
        log.info("MCAP running on http://localhost:{}", config.getPort());
    }
}
