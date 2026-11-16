package ru.betscan.scraper;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic DOM contract, not a captured bookmaker page. No external website requests. */
@EnabledIfSystemProperty(named = "playwright.tests", matches = "true")
class DomExtractionTest {
    @Test void extractsExactMarketsAndRejectsAmbiguousSelectors() throws Exception {
        try (var playwright = Playwright.create();
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setChannel(System.getProperty("playwright.channel", "chromium")).setHeadless(true))) {
            var page = browser.newPage();
            page.setContent("""
                <article class="event"><a href="https://pari.ru/sports/football/1/2">Event</a>
                <span class="home">A</span><span class="away">B</span><time datetime="2027-01-01T12:00:00Z"></time>
                <span class="p1">2,15</span><span class="px">3.2</span><span class="p2">4.0</span>
                <span class="total">1.5</span></article>
                <article class="event"><span class="live">LIVE</span></article>
                <article class="event"><span class="home">A</span><span class="home">Duplicate</span>
                <a href="https://pari.ru/sports/football/1/3">Event</a><time datetime="2027-01-01T12:00:00Z"></time></article>
                """);
            var selectors = Map.of("homeTeam", ".home", "awayTeam", ".away", "startTime", "time",
                    "startTimeAttribute", "datetime", "eventLink", "a", "homePrice", ".p1",
                    "drawPrice", ".px", "awayPrice", ".p2", "liveMarker", ".live");
            var script = new ClassPathResource("extract-rows.js").getContentAsString(StandardCharsets.UTF_8);
            var rows = (List<?>) page.locator(".event").evaluateAll(script, selectors);
            var first = (Map<?, ?>) rows.getFirst();
            assertThat(first.get("homePrice")).isEqualTo("2,15");
            assertThat(first.get("drawPrice")).isEqualTo("3.2");
            assertThat(first.get("awayPrice")).isEqualTo("4.0");
            assertThat(((Map<?, ?>) rows.get(1)).get("live")).isEqualTo(true);
            assertThat(((Map<?, ?>) rows.get(2)).get("error").toString()).contains("expected one element, found 2");
        }
    }
}
