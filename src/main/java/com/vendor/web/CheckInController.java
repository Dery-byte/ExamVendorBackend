package com.vendor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.vendor.model.Client;
import com.vendor.service.CheckInService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Called by each client's exam portal every few hours, with its own token as a Bearer token.
 * Too many bad tokens from one address are refused for a while.
 */
@RestController
@RequestMapping("/api/v1")
public class CheckInController {

    private static final int MAX_BAD_TOKENS = 20;
    private static final long WINDOW_MS = 10 * 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CheckInService checkIns;
    private final Map<String, long[]> badTokens = new ConcurrentHashMap<>();   // ip -> [count, window start]

    public CheckInController(CheckInService checkIns) {
        this.checkIns = checkIns;
    }

    /** Reads the body itself, so a bad token gets 401 whatever was sent. */
    @PostMapping("/check-in")
    public ResponseEntity<?> checkIn(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody(required = false) String body, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (blocked(ip)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "Too many failed check-ins. Try again later."));
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        Optional<Client> client = checkIns.authenticate(token);
        if (client.isEmpty()) {
            badTokens.compute(ip, (k, v) -> v == null || System.currentTimeMillis() - v[1] > WINDOW_MS
                    ? new long[]{1, System.currentTimeMillis()} : new long[]{v[0] + 1, v[1]});
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unknown or replaced token."));
        }
        JsonNode report;
        try {
            report = body == null || body.isBlank() ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "The check-in body is not valid JSON."));
        }
        return ResponseEntity.ok(checkIns.checkIn(client.get(), report, ip));
    }

    /** For uptime monitors. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private boolean blocked(String ip) {
        long[] v = badTokens.get(ip);
        if (v == null) return false;
        if (System.currentTimeMillis() - v[1] > WINDOW_MS) {
            badTokens.remove(ip);
            return false;
        }
        return v[0] >= MAX_BAD_TOKENS;
    }
}
