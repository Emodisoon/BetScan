package ru.betscan.model;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** A single bookmaker's full-time (90 minutes + stoppage time) football 1X2 quote. */
public record MatchOdds(Bookmaker bookmaker, String eventId, String homeTeam, String awayTeam,
                        Instant startsAt, String startTimeText, URI sourceUrl,
                        BigDecimal home, BigDecimal draw, BigDecimal away, Instant observedAt) {
    public MatchOdds {
        Objects.requireNonNull(bookmaker);
        Objects.requireNonNull(startsAt);
        Objects.requireNonNull(sourceUrl);
        Objects.requireNonNull(observedAt);
        if (eventId == null || eventId.isBlank() || homeTeam == null || homeTeam.isBlank()
                || awayTeam == null || awayTeam.isBlank()) {
            throw new IllegalArgumentException("Event ID and both teams are required");
        }
        for (var price : new BigDecimal[]{home, draw, away}) {
            if (price == null || price.compareTo(BigDecimal.ONE) <= 0) {
                throw new IllegalArgumentException("All three available decimal prices must be > 1");
            }
        }
        if (!startsAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("Only future prematch events are supported");
        }
    }
}
