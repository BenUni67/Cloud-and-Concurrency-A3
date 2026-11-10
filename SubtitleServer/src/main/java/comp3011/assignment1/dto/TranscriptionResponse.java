package comp3011.subtitleserver.dto;

public record TranscriptionResponse(
		String text,
		long inputTokens,
		long outputTokens,
		long totalTokens) {
	
}
