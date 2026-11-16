package comp3011.subtitleserver.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Used some assistance from ChatGPT in this class
// Only used for the boundary and help with building the HTTP Request
// Used in guidelines with what was allowed in the course and ED discussion

@Service
public class TranscriptionService {
	
	// httpClient sends request from Java to OpenAi
	// jsonMapper reads the JSON that OpenAi returns
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final StatsService statsService;
    
    // URL can be changed during regression testing to use a stub server now
    private final String transcriptionUrl;

    public TranscriptionService(JsonMapper jsonMapper, StatsService statsService, @Value("${openai.api-key}") String apiKey,
    		@Value("${openai.transcription-url:https://api.openai.com/v1/audio/transcriptions}") String transcriptionUrl) {
        this.jsonMapper = jsonMapper;
        this.statsService = statsService;
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        
        // Creating HTTP client that sends request to OpenAi
        this.httpClient = HttpClient.newHttpClient();
    }
    
    // Will send audio to whisper and return the subtitle text
    public CompletableFuture<JsonNode> transcribe(MultipartFile file) throws IOException {
    	
        String boundary = "----SubtitleServerBoundary";
        
        HttpRequest request = HttpRequest.newBuilder()
        		.uri(URI.create(transcriptionUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(createRequestBody(file, boundary))
                .build();

        // Sends the request asynchronously
        // Means that java doesn't sit and block the server thread while waiting for a reply
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
        	
        	if (response.statusCode() < 200 || response.statusCode() >= 300) {
        		throw new RuntimeException("Whisper request failed: " + response.statusCode());
        	}
        	
        	try {
        		// Convert whisper response into a JSOn file
        		JsonNode jsonFile = jsonMapper.readTree(response.body());
        		
        		// Track audio length
        		double seconds = jsonFile.path("usage").path("seconds").asDouble(json.path("duration").asDouble(0));
        		statsService.addTranscriptionSeconds(seconds);
        		return jsonFile;
        	} catch (Exception e) {
        		throw new RuntimeException(e);
        	}
        	
        });
    }

    // Creates the body of the request that we send to OpenAi
    // OpenAI needs two things, the ai model and the actual audio file
    private HttpRequest.BodyPublisher createRequestBody(MultipartFile file, String boundary) throws IOException {
    	
    	// This section here was created with the assistance of ChatGPT
        String fields = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + "whisper-1\r\n"

                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "verbose_json\r\n"

                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"timestamp_granularities[]\"\r\n\r\n"
                + "word\r\n"

                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"timestamp_granularities[]\"\r\n\r\n"
                + "segment\r\n";

        String audio = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; "
                + "name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";

        String end = "\r\n--" + boundary + "--\r\n";

        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(fields),
                HttpRequest.BodyPublishers.ofString(audio),
                HttpRequest.BodyPublishers.ofByteArray(file.getBytes()),
                HttpRequest.BodyPublishers.ofString(end)
        );
    }
}