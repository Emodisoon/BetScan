package ru.betscan.scraper;

import ru.betscan.model.Bookmaker;
import ru.betscan.model.MatchOdds;
import java.util.List;

public interface BookmakerScraper {
    ScanResult scan(Bookmaker bookmaker);

    record ScanResult(List<MatchOdds> matches, boolean partial, String message) {
        public ScanResult { matches = List.copyOf(matches); }
    }
}
