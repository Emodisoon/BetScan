package ru.betscan.scraper;

import ru.betscan.model.Bookmaker;
import ru.betscan.model.MatchOdds;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class QuoteParser {
    private static final ZoneId SITE_ZONE = ZoneId.of("Europe/Moscow");
    private QuoteParser() { }

    public static MatchOdds parse(Bookmaker bookmaker, RawQuote raw, Instant observedAt) {
        URI url = URI.create(raw.url());
        String path = url.getPath().replaceAll("/+$", "");
        if (path.isBlank()) throw new IllegalArgumentException("Missing event URL path");
        return new MatchOdds(bookmaker, path, raw.homeTeam().strip(), raw.awayTeam().strip(),
                startTime(raw.startTime(), observedAt), raw.startTime(), url,
                price(raw.homePrice()), price(raw.drawPrice()), price(raw.awayPrice()), observedAt);
    }

    static BigDecimal price(String text) {
        // Do not strip arbitrary text: suspended quotes, handicap and labels must fail closed.
        String value = text.strip().replace(',', '.');
        if (!value.matches("\\d{1,5}(?:\\.\\d{1,4})?")) {
            throw new IllegalArgumentException("Unavailable or unrecognized price");
        }
        return new BigDecimal(value);
    }

    static Instant startTime(String text, Instant now) {
        String value = text.strip();
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(SITE_ZONE).toInstant(); }
        catch (DateTimeParseException ignored) { }
        value = value.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replaceAll("\\s+", " ");
        var relative = java.util.regex.Pattern.compile("(сегодня|завтра)(?: в)? (\\d{1,2}:\\d{2})").matcher(value);
        if (relative.matches()) {
            var date = now.atZone(SITE_ZONE).toLocalDate();
            if (relative.group(1).equals("завтра")) date = date.plusDays(1);
            return date.atTime(LocalTime.parse(relative.group(2), DateTimeFormatter.ofPattern("H:mm")))
                    .atZone(SITE_ZONE).toInstant();
        }
        // A year is required; do not silently guess at New Year boundaries.
        for (String pattern : new String[]{"dd.MM.uuuu HH:mm", "d MMMM uuuu HH:mm"}) {
            try {
                return LocalDateTime.parse(value.replace(" в ", " "),
                        DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("ru"))
                                .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                        .atZone(SITE_ZONE).toInstant();
            } catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("Unrecognized start time: " + text);
    }

    public record RawQuote(String homeTeam, String awayTeam, String startTime,
                           String url, String homePrice, String drawPrice, String awayPrice) { }
}
