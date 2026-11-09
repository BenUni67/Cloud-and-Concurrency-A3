package comp3011.assignment1.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.dto.ShutdownResponse;
import comp3011.assignment1.dto.UptimeResponse;
import comp3011.assignment1.service.UptimeService;
import comp3011.assignment1.service.ShutdownService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private final UptimeService uptimeService;
	private final ShutdownService shutdownService;
	
	public AdminController(UptimeService uptimeService, ShutdownService shutdownService) {
		this.uptimeService = uptimeService;
		this.shutdownService = shutdownService;
	}
	
	@GetMapping("/uptime")
	public UptimeResponse getServerUptime() {
		return uptimeService.getUpTime();
	}
	
	// Response entity allows us to define what exact HTTP Response is returneds
	@PostMapping("/shutdown")
	public ResponseEntity<ShutdownResponse> shutdownServer() {
		boolean accepted = shutdownService.requestShutdown();
		
		if (!accepted) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(new ShutdownResponse("Graceful shutdown is already in progress"));
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ShutdownResponse("Graceful shutdown requested."));
	}
}


