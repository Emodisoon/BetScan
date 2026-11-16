package ru.betscan.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import ru.betscan.model.Bookmaker;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("scanner")
public record ScanProperties(
        boolean enabled, boolean headless, String browserChannel, String proxy,
        @Min(1000) @Max(120000) int timeoutMs,
        @Min(100) @Max(10000) int settleMs,
        @Min(1) @Max(500) int maxScrolls,
        @Min(1) @Max(10000) int maxMatches,
        @Min(1000) long staleAfterMs,
        @NotBlank String diagnosticsDirectory,
        @NotEmpty Map<Bookmaker, @Valid SiteProfile> sites) {
    public record SiteProfile(@NotBlank String url, String readySelector, String row,
                              String homeTeam, String awayTeam, String startTime,
                              String startTimeAttribute, String eventLink,
                              String homePrice, String drawPrice, String awayPrice,
                              String liveMarker, String scrollContainer) {
        public boolean configured() {
            return java.util.stream.Stream.of(row, homeTeam, awayTeam, startTime,
                    eventLink, homePrice, drawPrice, awayPrice)
                    .allMatch(value -> value != null && !value.isBlank());
        }
    }
}
