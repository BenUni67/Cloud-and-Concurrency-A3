package comp3011.assignment1.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

// Some assistance with ChatGPT was used for this, in accordance with what was stated was acceptable by course coordinator.

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"openai.api-key=fake-test-key"})
class TranscriptionIntegrationTest {

	// Creates a fake STT server, and instead of using the real OpenAI API, TranscriptionServer sends request here
    private static final HttpServer stubServer;
    private static final ExecutorService stubExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // Allows concurrency test to make STT service respond slowly
    private static final AtomicLong stubDelay = new AtomicLong(0);
    
    static {
        try {
            stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stubServer.setExecutor(stubExecutor);

            // Pretends to be /v1/audio/transcriptions
            stubServer.createContext("/v1/audio/transcriptions", exchange -> {

                try {
                    Thread.sleep(stubDelay.get());
                    
                    // Fake repsonse similar to OpenAI Response
                    String response = """
                            {
                              "text": "Fake Transcription",
                              "usage": {
                                "input_tokens": 10,
                                "output_tokens": 4,
                                "total_tokens": 14
                              }
                            }
                            """;

                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    
                    try (var output = exchange.getResponseBody()) {
                    	exchange.getResponseBody().write(responseBytes);
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                	exchange.close();
                }
            });
            stubServer.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Replaces openai.transcription-url inside TranscriptionService with fake STT server
    @DynamicPropertySource
    static void configureStubServer(DynamicPropertyRegistry registry) {
        registry.add("openai.transcription-url", () -> "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/v1/audio/transcriptions");
    }

    @LocalServerPort
    private int serverPort;
    
    // Stops STT server after all of the tests are finished
    @AfterAll
    static void stopStubServer() {
        stubServer.stop(0);
        stubExecutor.shutdownNow();
    }

    // Regression Test
    // Sends a fake audio file through the actual controller, actual TranscriptionServer and actual HttpClient
    @Test
    void transcriptionWorksUsingStubSttService() throws Exception {

        stubDelay.set(0);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = createTranscriptionRequest(serverPort);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Fake Transcription"));
        assertTrue(response.body().contains("\"inputTokens\":10"));
        assertTrue(response.body().contains("\"outputTokens\":4"));
        assertTrue(response.body().contains("\"totalTokens\":14"));
    }
    
    // Concurrency regression test
    // Sends more than 200 simultaneous blocking HTTP request through transcription controller
    // Fake STT server takes 2 seconds to response
    // While request are waiting, the tests check that another endpoint can still respond.
    @Test
    void handlesMoreThan200ConcurrentRequests() throws Exception {

        int requestCount = 225;
        // Makes every fake STT request take 2 seconds
        stubDelay.set(2000);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(requestCount);
        List<Future<Integer>> futures = new ArrayList<>();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpResponse<String> response = client.send(createTranscriptionRequest(serverPort), HttpResponse.BodyHandlers.ofString());
                    return response.statusCode();
                }));
            }

            // Waits until all 225 taks are ready
            ready.await();
            long testStart = System.currentTimeMillis();
            start.countDown();


            // Gives transcription request a moment to reach application
            Thread.sleep(300);

            long uptimeStart = System.currentTimeMillis();
            HttpRequest uptimeRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + serverPort + "/api/v1/admin/uptime"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> uptimeResponse = client.send(uptimeRequest, HttpResponse.BodyHandlers.ofString());
            long uptimeTime = System.currentTimeMillis() - uptimeStart;

            assertEquals(200, uptimeResponse.statusCode());
            assertTrue(uptimeTime < 1000, "Uptime endpoint took " + uptimeTime + "ms while " + requestCount + " transcription requests were active");

            // Checks that every transcription request eventually returns hTTp 200
            for (Future<Integer> future : futures) {
                assertEquals(200, future.get());
            }


            long totalTime = System.currentTimeMillis() - testStart;
            System.out.println(requestCount + " concurrent requests completed in " + totalTime + "ms");
        } finally {
            stubDelay.set(0);
        }
    }

    // Creates a fake browser multipart audio request to send to application's transcription endpoint
    private static HttpRequest createTranscriptionRequest(int port) {

        String boundary = "----RegressionTestBoundary";
        String audioHeader = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.webm\"\r\n"
                + "Content-Type: audio/webm\r\n\r\n";

        // Audio doesn't need real speech because STT server doesn't actually process it
        byte[] fakeAudio = new byte[] {1, 2, 3, 4, 5};
        String end = "\r\n--" + boundary + "--\r\n";
        
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(audioHeader),
                HttpRequest.BodyPublishers.ofByteArray(fakeAudio),
                HttpRequest.BodyPublishers.ofString(end)
        );

        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/transcriptions"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                .POST(body)
                .build();
    }
}