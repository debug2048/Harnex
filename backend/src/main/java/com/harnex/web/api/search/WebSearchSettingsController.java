package com.harnex.web.api.search;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.provider.search.WebSearchProbeService;

@RestController
@RequestMapping("/api/v1/config/web-search")
public final class WebSearchSettingsController {
    private final WebSearchProbeService probe;
    public WebSearchSettingsController(WebSearchProbeService probe) { this.probe = probe; }

    @PostMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> map ? (Map<String, Object>)map : Map.of();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(probe.probe(values));
    }
}
