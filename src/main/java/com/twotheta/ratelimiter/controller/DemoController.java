package com.twotheta.ratelimiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class DemoController {

    @GetMapping("/api/data")
    public Map<String, Object> getData() {
        return Map.of(
                "message", "Here is your data.",
                "timestamp", Instant.now().toString()
        );
    }
}
