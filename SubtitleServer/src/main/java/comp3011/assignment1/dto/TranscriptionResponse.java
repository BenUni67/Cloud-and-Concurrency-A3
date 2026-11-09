package comp3011.assignment1.dto;

public record TranscriptionResponse(
		String text,
		long inputTokens,
		long outputTokens,
		long totalTokens) {
	
}
