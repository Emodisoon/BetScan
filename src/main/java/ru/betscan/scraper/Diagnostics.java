package ru.betscan.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.betscan.config.ScanProperties;
import ru.betscan.model.Bookmaker;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Component
public class Diagnostics {
    private static final Logger log = LoggerFactory.getLogger(Diagnostics.class);
    private final Path root;
    private final ObjectMapper mapper;

    public Diagnostics(ScanProperties properties, ObjectMapper mapper) {
        this.root = Path.of(properties.diagnosticsDirectory());
        this.mapper = mapper;
    }

    public void save(Bookmaker bookmaker, Page page, String message, List<String> requests) {
        try {
            Path directory = root.resolve(bookmaker.name().toLowerCase(Locale.ROOT));
            Files.createDirectories(directory);
            // Only keep the latest attempt; remove previous artifacts to avoid mixing runs.
            for (String name : List.of("page.html", "page.png", "dom.json", "report.json")) {
                Files.deleteIfExists(directory.resolve(name));
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("bookmaker", bookmaker);
            report.put("capturedAt", Instant.now());
            report.put("message", message);
            report.put("failedRequests", List.copyOf(requests));
            List<String> captureErrors = new ArrayList<>();
            if (page != null && !page.isClosed()) {
                report.put("url", page.url());
                capture(captureErrors, "html", () -> Files.writeString(directory.resolve("page.html"), page.content()));
                capture(captureErrors, "screenshot", () -> page.screenshot(new Page.ScreenshotOptions()
                        .setPath(directory.resolve("page.png")).setTimeout(5000)));
                capture(captureErrors, "dom", () -> {
                    Object dom = page.evaluate("""
                        () => ({
                          title: document.title,
                          text: document.body.innerText.slice(0, 100000),
                          links: Array.from(document.querySelectorAll('a[href]')).slice(0, 500)
                            .map(a => ({text:a.innerText, href:a.href, className:a.className})),
                          testIds: Array.from(document.querySelectorAll('[data-testid]')).slice(0, 500)
                            .map(e => ({tag:e.tagName, id:e.dataset.testid, text:e.innerText.slice(0,200)})),
                          scrollContainers: Array.from(document.querySelectorAll('*'))
                            .filter(e => e.scrollHeight > e.clientHeight + 100 && /auto|scroll/.test(getComputedStyle(e).overflowY))
                            .slice(0,30).map(e => ({tag:e.tagName, id:e.id, className:e.className}))
                        })
                        """);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("dom.json").toFile(), dom);
                });
            }
            report.put("captureErrors", captureErrors);
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
        } catch (Exception exception) {
            log.warn("Could not save diagnostics for {}: {}", bookmaker, exception.toString());
        }
    }

    private void capture(List<String> errors, String name, Capture action) {
        try { action.run(); } catch (Exception exception) { errors.add(name + ": " + exception.getMessage()); }
    }

    @FunctionalInterface
    private interface Capture { void run() throws Exception; }
}
