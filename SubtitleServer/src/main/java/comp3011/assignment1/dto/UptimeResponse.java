package comp3011.assignment1.dto;

// The instant package represents a moment in time / timestamp within code.
import java.time.Instant;

public record UptimeResponse(Instant utcServerStart, Instant utcNow, double serverUptimeSeconds) {
}
