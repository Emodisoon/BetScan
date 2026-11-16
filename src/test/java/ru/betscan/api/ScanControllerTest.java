package ru.betscan.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"scanner.enabled=false", "logging.file.name=target/test-application.log"})
@AutoConfigureMockMvc
class ScanControllerTest {
    @Autowired MockMvc mvc;

    @Test void startsWithoutBrowserAndReportsUnscannedSources() throws Exception {
        mvc.perform(get("/api/matches")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scanRunning").value(false))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].status").value("NOT_SCANNED"));
    }

    @Test void filtersBookmakerAndRejectsUnknownValues() throws Exception {
        mvc.perform(get("/api/matches?bookmaker=PARI")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].bookmaker").value("PARI"));
        mvc.perform(get("/api/matches?bookmaker=UNKNOWN")).andExpect(status().isBadRequest());
    }
}
