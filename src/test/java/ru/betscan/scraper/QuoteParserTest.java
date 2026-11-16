package ru.betscan.scraper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.betscan.model.Bookmaker;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class QuoteParserTest {
    private final Instant now = Instant.parse("2026-12-31T20:00:00Z");

    @Test void parsesDecimalCommaAndAllOutcomes() {
        var quote = QuoteParser.parse(Bookmaker.PARI, raw("Завтра в 01:30", "2,15", "3.20", "3.50"), now);
        assertThat(quote.home()).isEqualByComparingTo("2.15");
        assertThat(quote.startsAt()).isEqualTo("2026-12-31T22:30:00Z");
        assertThat(quote.eventId()).isEqualTo("/sports/football/123/456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"—", "", "Заблокировано", "1.5 2.0", "0", "1", "-2.10", "NaN", "2.3x"})
    void rejectsUnavailableOrInvalidPrice(String price) {
        assertThatThrownBy(() -> QuoteParser.parse(Bookmaker.PARI, raw("Завтра в 12:00", price, "3", "4"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Сегодня в 22:59", "2026-12-31T20:00:00Z", "01.01 12:00", "LIVE", "31.02.2027 12:00"})
    void rejectsPastOrAmbiguousStart(String start) {
        assertThatThrownBy(() -> QuoteParser.parse(Bookmaker.BETBOOM, raw(start, "2", "3", "4"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void respectsExplicitOffsetAndMoscowLocalTime() {
        assertThat(QuoteParser.startTime("2027-01-01T12:00:00+03:00", now)).isEqualTo("2027-01-01T09:00:00Z");
        assertThat(QuoteParser.startTime("01.01.2027 12:00", now)).isEqualTo("2027-01-01T09:00:00Z");
    }

    private QuoteParser.RawQuote raw(String start, String home, String draw, String away) {
        return new QuoteParser.RawQuote("Team A", "Team B", start,
                "https://pari.ru/sports/football/123/456", home, draw, away);
    }
}
