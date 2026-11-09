package comp3011.assignment1.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import comp3011.assignment1.dto.UptimeResponse;
import comp3011.assignment1.service.ShutdownService;
import comp3011.assignment1.service.UptimeService;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UptimeService uptimeService;

    @MockitoBean
    private ShutdownService shutdownService;

    @Test
    void uptimeReturnsCorrectResult() throws Exception {

        Instant start = Instant.parse("2026-09-15T00:00:00Z");
        Instant now = Instant.parse("2026-09-15T00:00:10Z");

        when(uptimeService.getUpTime()).thenReturn(new UptimeResponse(start, now, 10.0));

        mockMvc.perform(get("/api/v1/admin/uptime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utcServerStart").value("2026-09-15T00:00:00Z"))
                .andExpect(jsonPath("$.utcNow").value("2026-09-15T00:00:10Z"))
                .andExpect(jsonPath("$.serverUptimeSeconds").value(10.0));
    }

    @Test
    void shutdownReturns202WhenAccepted() throws Exception {

        when(shutdownService.requestShutdown()).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/shutdown"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Graceful shutdown requested."));
    }
}
