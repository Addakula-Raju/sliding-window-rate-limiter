package com.twotheta.ratelimiter.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.max-requests=3",
        "ratelimit.window-seconds=10"
})
class DemoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsRequestsUnderLimitThenReturns429WithHeaders() throws Exception {
        String clientId = "integration-client-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/data").header("X-Client-Id", clientId))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-RateLimit-Remaining"));
        }

        mockMvc.perform(get("/api/data").header("X-Client-Id", clientId))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void differentClientIdsAreIsolated() throws Exception {
        String clientA = "client-a-" + System.nanoTime();
        String clientB = "client-b-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/data").header("X-Client-Id", clientA))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/data").header("X-Client-Id", clientA))
                .andExpect(status().isTooManyRequests());

        // clientB has a fresh quota despite clientA being exhausted.
        mockMvc.perform(get("/api/data").header("X-Client-Id", clientB))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
    }

    @Test
    void missingClientIdHeaderStillWorksViaAnonymousBucket() throws Exception {
        mockMvc.perform(get("/api/data"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }
}
