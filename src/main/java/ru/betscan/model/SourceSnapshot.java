package ru.betscan.model;

import java.time.Instant;
import java.util.List;

public record SourceSnapshot(Bookmaker bookmaker, Status status, Instant attemptedAt,
                             Instant lastSuccessAt, String message, List<MatchOdds> matches) {
    public enum Status { NOT_SCANNED, SUCCESS, PARTIAL, ERROR }
    public SourceSnapshot { matches = List.copyOf(matches); }
    public static SourceSnapshot initial(Bookmaker bookmaker) {
        return new SourceSnapshot(bookmaker, Status.NOT_SCANNED, null, null, "Not scanned yet", List.of());
    }
}
