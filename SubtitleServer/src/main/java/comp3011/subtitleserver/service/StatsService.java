package comp3011.subtitleserver.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.stereotype.Service;

import comp3011.subtitleserver.dto.GlobalStatsResponse;

@Service
public class StatsService {
	// AtomicLong makes updating these values safe when multiple request happen at the same exact time
	// Without it two requests could both read and update the same value at once, which might cause a wrong total at the end
	private final AtomicLong inputTokens = new AtomicLong(0);
	private final AtomicLong outputTokens = new AtomicLong(0);
	
	// tracks the duration of audio transcribed across every request made
	private final DoubleAdder transcriptionSeconds = new DoubleAdder();
	
	public GlobalStatsResponse getStats() {
		return new GlobalStatsResponse(inputTokens.get(), outputTokens.get());
	}
	
	public void addTokenStats(long input, long output) {
		inputTokens.addAndGet(input);
		outputTokens.addAndGet(output);
	}
	
	// adds the duration of a completed transcription
	public void addTranscriptionSeconds(double seconds) {
		if (seconds > 0) {
			transcriptionSeconds.add(seconds);
		}
	}
	
	// returns the total transcription usage
	public double getTranscriptionSeconds() {
		return transcriptionSeconds.sum();
	}
}
