package ru.betscan.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.betscan.config.ScanProperties;
import ru.betscan.model.*;
import ru.betscan.scraper.BookmakerScraper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class ScanService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private final BookmakerScraper scraper;
    private final ScanProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Map<Bookmaker, SourceSnapshot>> snapshots;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("bookmaker-scan").factory());

    @org.springframework.beans.factory.annotation.Autowired
    public ScanService(BookmakerScraper scraper, ScanProperties properties) {
        this(scraper, properties, Clock.systemUTC());
    }

    ScanService(BookmakerScraper scraper, ScanProperties properties, Clock clock) {
        this.scraper = scraper;
        this.properties = properties;
        this.clock = clock;
        Map<Bookmaker, SourceSnapshot> initial = new EnumMap<>(Bookmaker.class);
        for (Bookmaker bookmaker : Bookmaker.values()) initial.put(bookmaker, SourceSnapshot.initial(bookmaker));
        snapshots = new AtomicReference<>(Map.copyOf(initial));
    }

    @Scheduled(fixedDelayString = "${scanner.interval-ms:300000}",
            initialDelayString = "${scanner.initial-delay-ms:5000}")
    public void scheduledScan() {
        if (properties.enabled()) startScan();
    }

    public boolean startScan() {
        if (!running.compareAndSet(false, true)) return false;
        try {
            worker.submit(() -> {
                try {
                    for (Bookmaker bookmaker : Bookmaker.values()) {
                        if (Thread.currentThread().isInterrupted()) break;
                        scanOne(bookmaker);
                    }
                } finally { running.set(false); }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            running.set(false);
            return false;
        }
    }

    private void scanOne(Bookmaker bookmaker) {
        Instant attemptedAt = clock.instant();
        SourceSnapshot previous = snapshots.get().get(bookmaker);
        SourceSnapshot next;
        try {
            var result = scraper.scan(bookmaker);
            if (result.matches().isEmpty()) throw new IllegalStateException("Empty scrape is not a verified empty line");
            next = new SourceSnapshot(bookmaker, result.partial() ? SourceSnapshot.Status.PARTIAL : SourceSnapshot.Status.SUCCESS,
                    attemptedAt, clock.instant(), result.message(), result.matches());
            log.info("{}: {}", bookmaker, result.message());
        } catch (RuntimeException exception) {
            log.warn("{} scan failed; see diagnostics: {}", bookmaker, exception.toString());
            next = new SourceSnapshot(bookmaker, SourceSnapshot.Status.ERROR, attemptedAt,
                    previous.lastSuccessAt(), exception.getMessage(), previous.matches());
        }
        final SourceSnapshot updated = next;
        snapshots.updateAndGet(current -> {
            var copy = new EnumMap<Bookmaker, SourceSnapshot>(Bookmaker.class);
            copy.putAll(current);
            copy.put(bookmaker, updated);
            return Map.copyOf(copy);
        });
    }

    public View view(Bookmaker filter) {
        Instant now = clock.instant();
        var snapshot = snapshots.get();
        List<SourceView> sources = Arrays.stream(Bookmaker.values())
                .filter(bookmaker -> filter == null || bookmaker == filter)
                .map(snapshot::get)
                .map(source -> new SourceView(source.bookmaker(), source.status(), source.attemptedAt(),
                        source.lastSuccessAt(), source.lastSuccessAt() == null
                        || Duration.between(source.lastSuccessAt(), now).toMillis() >= properties.staleAfterMs(),
                        source.message(), source.matches().stream().filter(match -> match.startsAt().isAfter(now))
                        .sorted(Comparator.comparing(MatchOdds::startsAt).thenComparing(MatchOdds::eventId)).toList()))
                .toList();
        return new View(now, running.get(), sources);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    public record View(Instant generatedAt, boolean scanRunning, List<SourceView> sources) { }
    public record SourceView(Bookmaker bookmaker, SourceSnapshot.Status status, Instant attemptedAt,
                             Instant lastSuccessAt, boolean stale, String message, List<MatchOdds> matches) { }
}
