package comp3011.assignment1.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import comp3011.assignment1.dto.GlobalStatsResponse;
import comp3011.assignment1.service.StatsService;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

	// MockMvc instance, which lets use simulate HTTP request
	// against controller without starting a real server
    @Autowired
    private MockMvc mockMvc;
    
    // Replaces real StatsService in the context with a Mock one,
    // so we can control what it returns
    @MockitoBean
    private StatsService statsService;

    @Test
    void getGlobalStatsReturnsCurrentTokenCounts() throws Exception {
    	
    	// When getStats() is called, return a fixed GlobalStatsResponse
        when(statsService.getStats()).thenReturn(new GlobalStatsResponse(100, 25));
        
        // Simulate a get request to the endpoint and verify the following
        mockMvc.perform(get("/api/v1/global/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").value(100))
                .andExpect(jsonPath("$.outputTokens").value(25));
    }
}
