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

import comp3011.subtitleserver.dto.TranscriptionResponse;
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

    public CompletableFuture<TranscriptionResponse> transcribe(MultipartFile file) throws IOException {
    	
    	// The multipart request contains multiple parts of data, including model name and audio file in this case
    	// The boundary is used to seperate the parts of the request to OpenAI
    	
        String boundary = "----SubtitleServerBoundary";
        
        // Request that HTTP sends to OpenAi
        HttpRequest request = HttpRequest.newBuilder()
        		// OpenAI speech to text endpoint
        		.uri(URI.create(transcriptionUrl))
                .header("Authorization", "Bearer " + apiKey)
                // Tells OpenAi we are sending multipart data, and that boundary seperates each part
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(createRequestBody(file, boundary))
                .build();
        
        long startTime = System.currentTimeMillis();
        System.out.println("Sending audio to openAI");

        // Sends the request asynchronously
        // Means that java doesn't sit and block the server thread while waiting for a reply
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {

        	long timeTaken = System.currentTimeMillis() - startTime;
            System.out.println(
                    "OpenAI responded with status "
                    + response.statusCode()
                    + " after "
                    + timeTaken
                    + "ms"
            );
        	
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenAI request failed: " + response.statusCode());
            }
            try {
            	// Reads the OpenAi response
                JsonNode json = jsonMapper.readTree(response.body());
                String text = json.path("text").asString();
                long inputTokens = json.path("usage").path("input_tokens").asLong();
                long outputTokens = json.path("usage").path("output_tokens").asLong();
                long totalTokens = json.path("usage").path("total_tokens").asLong();
                
                // Add the token usage to stats
                statsService.addTokenStats(inputTokens, outputTokens);
                return new TranscriptionResponse(text, inputTokens, outputTokens, totalTokens);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Creates the body of the request that we send to OpenAi
    // OpenAI needs two things, the ai model and the actual audio file
    private HttpRequest.BodyPublisher createRequestBody(MultipartFile file, String boundary) throws IOException {

    	// Tells Open ai the model, and the boundary
        String model = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + "gpt-4o-mini-transcribe\r\n";

        // Tells Open ai the audio file, and the filename and that it contains WebM audio
        String audio = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; "
                + "name=\"file\"; filename=\"recording.webm\"\r\n"
                + "Content-Type: audio/webm\r\n\r\n";

        String end = "\r\n--" + boundary + "--\r\n";
        
        // Joins the parts together for correct request 
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(model),
                HttpRequest.BodyPublishers.ofString(audio),
                HttpRequest.BodyPublishers.ofByteArray(file.getBytes()),
                HttpRequest.BodyPublishers.ofString(end)
        );
    }
}