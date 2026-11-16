package ru.betscan.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.betscan.model.Bookmaker;
import ru.betscan.service.ScanService;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ScanController {
    private final ScanService service;
    public ScanController(ScanService service) { this.service = service; }

    @GetMapping("/matches")
    public ScanService.View matches(@RequestParam(required = false) Bookmaker bookmaker) {
        return service.view(bookmaker);
    }

    @PostMapping("/scans")
    public ResponseEntity<Map<String, String>> scan() {
        if (!service.startScan()) return ResponseEntity.status(409).body(Map.of("status", "already_running_or_stopping"));
        return ResponseEntity.accepted().location(URI.create("/api/matches")).body(Map.of("status", "started"));
    }
}
