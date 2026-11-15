package comp3011.subtitleserver.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.subtitleserver.dto.GlobalStatsResponse;
import comp3011.subtitleserver.service.StatsService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/global")
public class StatsController {
	private final StatsService statsService;
	
	public StatsController(StatsService statsService) {
		this.statsService = statsService;
	}
	
	@GetMapping("/stats")
	public GlobalStatsResponse getGlobalStats() {
		return statsService.getStats();
	}
	
	// returns the total transcription usage in seconds
	@GetMapping("/usage")
	public Map<String, Double> getUsage() {
		return Map.of("usage", statsService.getTranscriptionSeconds());
	}
}
