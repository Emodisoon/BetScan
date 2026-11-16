package ru.betscan.scraper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.betscan.config.ScanProperties;
import ru.betscan.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class PlaywrightBookmakerScraper implements BookmakerScraper {
    private final ScanProperties properties;
    private final Diagnostics diagnostics;
    private final ObjectMapper mapper;
    private final String extractScript;

    public PlaywrightBookmakerScraper(ScanProperties properties, Diagnostics diagnostics, ObjectMapper mapper)
            throws IOException {
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.mapper = mapper;
        this.extractScript = new ClassPathResource("extract-rows.js").getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public ScanResult scan(Bookmaker bookmaker) {
        var profile = properties.sites().get(bookmaker);
        if (profile == null) throw new ScanException("Missing site profile for " + bookmaker);
        List<String> failures = new ArrayList<>();
        boolean captured = false;
        // Every Playwright object is created, used and closed on this worker thread.
        try (Playwright playwright = Playwright.create()) {
            var options = new BrowserType.LaunchOptions().setHeadless(properties.headless());
            if (hasText(properties.browserChannel())) options.setChannel(properties.browserChannel());
            if (hasText(properties.proxy())) options.setProxy(new Proxy(properties.proxy()));
            try (Browser browser = playwright.chromium().launch(options);
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                         .setLocale("ru-RU").setTimezoneId("Europe/Moscow").setViewportSize(1440, 1000))) {
                Page page = context.newPage();
                page.setDefaultTimeout(properties.timeoutMs());
                page.setDefaultNavigationTimeout(properties.timeoutMs());
                page.onRequestFailed(request -> {
                    if (failures.size() < 50) {
                        try {
                            URI uri = URI.create(request.url());
                            failures.add(uri.getHost() + uri.getPath() + ": " + request.failure());
                        } catch (IllegalArgumentException ignored) { }
                    }
                });
                String message = "Scan did not complete";
                try {
                    Response response = page.navigate(profile.url(),
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    if (response == null || response.status() >= 400) {
                        throw new ScanException("Navigation HTTP status: " + (response == null ? "unknown" : response.status()));
                    }
                    if (hasText(profile.readySelector())) page.locator(profile.readySelector()).first().waitFor();
                    else page.waitForTimeout(properties.settleMs());
                    if (!profile.configured()) {
                        // First run is intentionally diagnostic, until actual site markup is available.
                        page.waitForTimeout(5000);
                        throw new ScanException("NEEDS_CONFIGURATION: captured page; configure scanner.sites."
                                + bookmaker + " selectors using diagnostics");
                    }
                    page.locator(profile.row()).first().waitFor();
                    ScanResult result = extract(page, bookmaker, profile);
                    message = result.message();
                    return result;
                } catch (RuntimeException exception) {
                    message = exception.getMessage();
                    throw exception;
                } finally {
                    diagnostics.save(bookmaker, page, message, failures);
                    captured = true;
                }
            }
        } catch (RuntimeException exception) {
            if (!captured) diagnostics.save(bookmaker, null, exception.getMessage(), failures);
            throw new ScanException("Scan failed for " + bookmaker + ": " + exception.getMessage(), exception);
        }
    }

    private ScanResult extract(Page page, Bookmaker bookmaker, ScanProperties.SiteProfile profile) {
        Map<String, MatchOdds> matches = new LinkedHashMap<>();
        Set<String> rejected = new LinkedHashSet<>();
        boolean reachedEnd = false;
        int stableRounds = 0;
        for (int round = 0; round < properties.maxScrolls(); round++) {
            List<Map<String, Object>> rows = mapper.convertValue(
                    page.locator(profile.row()).evaluateAll(extractScript, mapper.convertValue(profile, Map.class)),
                    new TypeReference<>() { });
            int previousCount = matches.size();
            for (var row : rows) {
                if (Boolean.TRUE.equals(row.get("live"))) continue;
                if (row.containsKey("error")) { rejected.add(row.get("error").toString()); continue; }
                try {
                    var raw = new QuoteParser.RawQuote(text(row, "homeTeam"), text(row, "awayTeam"),
                            text(row, "startTime"), text(row, "url"), text(row, "homePrice"),
                            text(row, "drawPrice"), text(row, "awayPrice"));
                    URI eventUrl = URI.create(raw.url());
                    if (!Objects.equals(URI.create(profile.url()).getHost(), eventUrl.getHost())
                            || !"https".equals(eventUrl.getScheme())) {
                        throw new IllegalArgumentException("Unexpected event URL origin");
                    }
                    var match = QuoteParser.parse(bookmaker, raw, Instant.now());
                    if (matches.size() < properties.maxMatches() || matches.containsKey(match.eventId())) {
                        matches.put(match.eventId(), match);
                    }
                } catch (IllegalArgumentException exception) {
                    if (rejected.size() < 100) rejected.add(exception.getMessage());
                }
            }
            if (matches.size() >= properties.maxMatches()) break;
            boolean atBottom = (boolean) page.evaluate("""
                selector => {
                  const e = selector ? document.querySelector(selector) : document.scrollingElement;
                  if (!e) throw new Error('Scroll container not found');
                  const atBottom = e.scrollTop + e.clientHeight >= e.scrollHeight - 2;
                  e.scrollBy(0, Math.max(200, e.clientHeight * 0.8));
                  return atBottom;
                }
                """, profile.scrollContainer());
            stableRounds = atBottom && previousCount == matches.size() ? stableRounds + 1 : 0;
            if (stableRounds >= 3) { reachedEnd = true; break; }
            page.waitForTimeout(properties.settleMs());
        }
        if (matches.isEmpty()) {
            throw new ScanException("No valid prematch 1X2 quotes. Check page/market selectors. Rejections: " + rejected);
        }
        boolean partial = !reachedEnd || !rejected.isEmpty();
        return new ScanResult(new ArrayList<>(matches.values()), partial,
                "Collected " + matches.size() + " matches; scroll end=" + reachedEnd + "; rejections=" + rejected);
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
}
