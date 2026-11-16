package ru.betscan.service;

import org.junit.jupiter.api.Test;
import ru.betscan.config.ScanProperties;
import ru.betscan.model.*;
import ru.betscan.scraper.BookmakerScraper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ScanServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    @Test void preservesSuccessfulSnapshotAndContinuesOtherSourceOnFailure() {
        AtomicBoolean fail = new AtomicBoolean();
        BookmakerScraper scraper = bookmaker -> {
            if (fail.get() && bookmaker == Bookmaker.BETBOOM) throw new IllegalStateException("network down");
            return new BookmakerScraper.ScanResult(List.of(quote(bookmaker)), false, "ok");
        };
        try (var service = new ScanService(scraper, properties(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThat(service.view(null).sources()).allMatch(source -> source.stale());
            run(service);
            fail.set(true);
            run(service);
            var failed = service.view(Bookmaker.BETBOOM).sources().getFirst();
            assertThat(failed.status()).isEqualTo(SourceSnapshot.Status.ERROR);
            assertThat(failed.matches()).hasSize(1);
            assertThat(failed.lastSuccessAt()).isEqualTo(NOW);
            assertThat(service.view(Bookmaker.PARI).sources().getFirst().status()).isEqualTo(SourceSnapshot.Status.SUCCESS);
        }
    }

    @Test void doesNotQueueOverlappingScans() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BookmakerScraper scraper = bookmaker -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new BookmakerScraper.ScanResult(List.of(quote(bookmaker)), false, "ok");
        };
        try (var service = new ScanService(scraper, properties(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThat(service.startScan()).isTrue();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.startScan()).isFalse();
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> !service.view(null).scanRunning());
        } finally { release.countDown(); }
    }

    @Test void emptyResultIsAnError() {
        try (var service = new ScanService(bookmaker -> new BookmakerScraper.ScanResult(List.of(), false, "empty"),
                properties(), Clock.fixed(NOW, ZoneOffset.UTC))) {
            run(service);
            assertThat(service.view(null).sources()).allMatch(source -> source.status() == SourceSnapshot.Status.ERROR);
        }
    }

    private void run(ScanService service) {
        assertThat(service.startScan()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.view(null).scanRunning());
    }

    private MatchOdds quote(Bookmaker bookmaker) {
        return new MatchOdds(bookmaker, "event/1", "A", "B", NOW.plusSeconds(3600), "today", URI.create("https://pari.ru/event/1"),
                new BigDecimal("2.1"), new BigDecimal("3.2"), new BigDecimal("3.5"), NOW);
    }

    private ScanProperties properties() {
        return new ScanProperties(false, true, "", "", 30000, 100, 5, 50, 600000, "target/diagnostics", Map.of());
    }
}
