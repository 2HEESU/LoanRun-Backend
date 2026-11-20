package com.loanrun.loanproduct.service;

import com.loanrun.loanproduct.repository.CatalogRepository;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Populates the FSS catalog once on startup only when enabled and the DB cache is still empty. */
@Component
public class CatalogBootstrapRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrapRunner.class);
    private final CatalogRepository repository;
    private final CatalogRefreshService refresh;
    private final boolean enabled;

    public CatalogBootstrapRunner(
            CatalogRepository repository,
            CatalogRefreshService refresh,
            @Value("${FSS_ENABLED:false}") boolean enabled) {
        this.repository = repository;
        this.refresh = refresh;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        CompletableFuture.runAsync(this::syncIfEmpty);
    }

    void syncIfEmpty() {
        if (!enabled) return;
        try {
            if (repository.ready()) return;
            refresh.refresh();
        } catch (RuntimeException ex) {
            log.warn("Initial FSS catalog bootstrap skipped ({})", ex.getClass().getSimpleName());
        }
    }
}
