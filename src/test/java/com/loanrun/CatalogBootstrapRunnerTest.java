package com.loanrun;

import static org.mockito.Mockito.*;

import com.loanrun.loanproduct.repository.CatalogRepository;
import com.loanrun.loanproduct.service.CatalogBootstrapRunner;
import com.loanrun.loanproduct.service.CatalogRefreshService;
import org.junit.jupiter.api.Test;

class CatalogBootstrapRunnerTest {
    @Test
    void disabledBootstrapDoesNotTouchRepositoryOrRefreshService() {
        var repository = mock(CatalogRepository.class);
        var refresh = mock(CatalogRefreshService.class);
        var runner = new CatalogBootstrapRunner(repository, refresh, false);
        runner.onReady();
        verifyNoInteractions(repository, refresh);
    }

    @Test
    void enabledBootstrapSkipsRefreshWhenCatalogIsAlreadyReady() {
        var repository = mock(CatalogRepository.class);
        var refresh = mock(CatalogRefreshService.class);
        when(repository.ready()).thenReturn(true);
        new CatalogBootstrapRunner(repository, refresh, true).onReady();
        verify(repository, timeout(3000)).ready();
        verifyNoInteractions(refresh);
    }

    @Test
    void enabledBootstrapTriggersOneRefreshWhenCatalogIsEmpty() {
        var repository = mock(CatalogRepository.class);
        var refresh = mock(CatalogRefreshService.class);
        when(repository.ready()).thenReturn(false);
        new CatalogBootstrapRunner(repository, refresh, true).onReady();
        verify(refresh, timeout(3000)).refresh();
    }

    @Test
    void repositoryFailureIsLoggedSafelyAndDoesNotCallRefresh() {
        var repository = mock(CatalogRepository.class);
        var refresh = mock(CatalogRefreshService.class);
        when(repository.ready()).thenThrow(new IllegalStateException("db unavailable"));
        new CatalogBootstrapRunner(repository, refresh, true).onReady();
        verify(repository, timeout(3000)).ready();
        verifyNoInteractions(refresh);
    }
}
