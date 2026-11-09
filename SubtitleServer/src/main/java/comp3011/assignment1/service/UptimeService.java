package comp3011.assignment1.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import comp3011.assignment1.dto.UptimeResponse;

@Service
public class UptimeService {
	private final Instant serverStartTime;
	
	public UptimeService() {
		// Grabs the timestamp for the exact time when this code runs
		this.serverStartTime = Instant.now();
	}
	
	public UptimeResponse getUpTime() {
		Instant timeNow = Instant.now();
		
		// https://medium.com/@AlexanderObregon/javas-duration-between-method-explained-a15e2cc54c8b\
		// Used AI to help me format the floating point seconds in duration
		double rawSeconds = Duration.between(serverStartTime, timeNow).toNanos() / 1_000_000_000.0;
		
		// https://stackoverflow.com/questions/2808535/round-a-double-to-2-decimal-places
		double uptimeSeconds = BigDecimal.valueOf(rawSeconds).setScale(2, RoundingMode.HALF_UP).doubleValue();

		return new UptimeResponse(serverStartTime, timeNow, uptimeSeconds);
	} 
}
