package comp3011;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.concurrent.Future;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;

// Some of the logic with the audio was done with the assistance of ChatGPT, as per assignment guideline

public class SubtitleClient implements AutoCloseable {

    private static final URI SERVER = URI.create("http://localhost:8080/api/v1/subtitles");
    private static final long FIRST_CHUNK_DURATION_US = 6_000_000L;
    private static final long CHUNK_DURATION_US = 15_000_000L;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "subtitle-client");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private Future<?> runningTask;

    // starts transcription without blocking the videos playback
    public void start(File video, Consumer<List<SubtitleInstance>> onInstances, Consumer<String> onError) {
    	startAt(video, 0, onInstances, onError);
    };
    
    // start transcribing from a chosen video position
    public synchronized void startAt(File video, long positionUs, Consumer<List<SubtitleInstance>> onInstances,Consumer<String> onError) {
        cancel();
        long startUs = Math.max(0, positionUs);
        runningTask = executor.submit(() ->
                processAudio(video, startUs, onInstances, onError)
        );
    }

    // cancel any outstanding transcription
    public synchronized void cancel() {
        if (runningTask != null) {
            runningTask.cancel(true);
            runningTask = null;
        }
    }

    private void processAudio(File video, long startUs, Consumer<List<SubtitleInstance>> onInstances, Consumer<String> onError) {
        Path tempFile = null;
        FFmpegFrameRecorder recorder = null;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            
            // move subtitle decoder to the requested playback position
            if (startUs > 0) {
            	grabber.setTimestamp(startUs);
            }

            if (!grabber.hasAudio()) {
                onError.accept("Video contains no audio.");
                return;
            }

            long chunkStartUs = 0;
            boolean firstChunk = true;
            Frame frame;

            try {
                while (!Thread.currentThread().isInterrupted() && (frame = grabber.grabSamples()) != null) {
                    if (frame.samples == null) {
                        continue;
                    }

                    long timestampUs = Math.max(0, grabber.getTimestamp());

                    // ignore audio before our seek position
                    if (timestampUs < startUs) {
                    	continue;
                    }
                    
                    // a finished chunk is now able to be transcribed
                    if (recorder != null && timestampUs - chunkStartUs >= (firstChunk ? FIRST_CHUNK_DURATION_US : CHUNK_DURATION_US)) {
                        recorder.stop();
                        recorder.close();
                        recorder = null;

                        sendChunk(tempFile, chunkStartUs, onInstances, onError);
                        firstChunk = false;
                        Files.deleteIfExists(tempFile);
                        tempFile = null;
                    }

                    // start recording the next chunk
                    if (recorder == null) {
                        chunkStartUs = timestampUs;
                        tempFile = Files.createTempFile("subtitle-", ".wav");
                        recorder = new FFmpegFrameRecorder(tempFile.toFile(), 0, 0, 1);

                        recorder.setFormat("wav");
                        recorder.setSampleRate(16000);
                        recorder.setAudioChannels(1);
                        recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
                        recorder.start();
                    }

                    // convert the original media audios
                    recorder.recordSamples(grabber.getSampleRate(), grabber.getAudioChannels(), frame.samples);
                }

                // send the final chunk
                if (recorder != null && !Thread.currentThread().isInterrupted()) {
                    recorder.stop();
                    recorder.close();
                    recorder = null;

                    sendChunk(tempFile, chunkStartUs, onInstances, onError);
                }

            } finally {
                if (recorder != null) {
                    recorder.close();
                }

                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            }

        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                onError.accept(e.getMessage());
            }
        }
    }

    // Uploads one of the WAV chunks to the spring boot server, localhost 8008
    private void sendChunk(Path wavFile, long offsetUs, Consumer<List<SubtitleInstance>> oninstances, Consumer<String> onError) {

        try {
            String boundary = "----A3SubtitleBoundary";
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; "
                    + "name=\"file\"; filename=\"chunk.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            String ending = "\r\n--" + boundary + "--\r\n";

            HttpRequest request = HttpRequest.newBuilder(SERVER)
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type",
                            "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(header), HttpRequest.BodyPublishers.ofFile(wavFile), HttpRequest.BodyPublishers.ofString(ending)
                    ))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Subtitle server returned HTTP " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            List<SubtitleInstance> instances = parseinstances(json, offsetUs);
            oninstances.accept(instances);
            System.out.println("Received " + instances.size() + " subtitle instances.");

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            onError.accept(e.getMessage());
        }
    }
    
    // retries temporary connection failures without stopping playback
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response;
                }
                int status = response.statusCode();
                // dont retry invalid requests or auth errors
                if (status >= 400 && status < 500 && status != 429) {
                    throw new IllegalStateException("Subtitle server returned HTTP " + status);
                }
                System.err.println("Subtitle server unavailable (HTTP " + status + "), retrying...");
            } catch (java.io.IOException e) {
                System.err.println("Subtitle connection failed, retrying: " + e.getMessage());
            }
            Thread.sleep(2000);
        }
        throw new InterruptedException("Subtitle client stopped.");
    }

    // converts whisper segment timestamps into video timestamps
    private List<SubtitleInstance> parseinstances(JsonNode json, long offsetUs) {
        List<SubtitleInstance> timedWords = new ArrayList<>();
        JsonNode segments = json.path("segments");
        JsonNode words = json.path("words");

        if (!segments.isArray()) {
            throw new IllegalArgumentException("Subtitle response has no segments.");
        }

        for (JsonNode segment : segments) {
            double noSpeechProb = segment.path("no_speech_prob").asDouble(0);
            double avgLogProb = segment.path("avg_logprob").asDouble(0);

            // ignore segments likely to contain no speech
            if (noSpeechProb > 0.6 && avgLogProb < -1.0) {
                continue;
            }

            double segmentStart = segment.path("start").asDouble();
            double segmentEnd = segment.path("end").asDouble();

            boolean foundWords = false;

            if (words.isArray()) {
                for (JsonNode word : words) {

                    double wordStart = word.path("start").asDouble();
                    double wordEnd = word.path("end").asDouble();

                    double midpoint = (wordStart + wordEnd) / 2.0;

                    // associate the word with this speech segment
                    if (midpoint < segmentStart || midpoint >= segmentEnd) {
                        continue;
                    }

                    String value = word.path("word").asText("").trim();

                    if (value.isEmpty() || wordEnd <= wordStart) {
                        continue;
                    }

                    timedWords.add(new SubtitleInstance(offsetUs + Math.round(wordStart * 1_000_000), offsetUs + Math.round(wordEnd * 1_000_000), value));

                    foundWords = true;
                }
            }

            // fall back to aprrox word timing if Whisper returns
            // a segment but no individual words for it
            if (!foundWords) {
                String text = segment.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                
                String[] pieces = text.split("\\s+");

                long start = offsetUs + Math.round(segmentStart * 1_000_000);
                long end = offsetUs + Math.round(segmentEnd * 1_000_000);

                if (end <= start) {
                    continue;
                }

                for (int i = 0; i < pieces.length; i++) {
                    long wordStart = start + (end - start) * i / pieces.length;
                    long wordEnd = start + (end - start) * (i + 1) / pieces.length;
                    timedWords.add(new SubtitleInstance(wordStart, wordEnd, pieces[i]));
                }
            }
        }
        return SubtitleFormatter.format(timedWords);
    }

    @Override
    public synchronized void close() {
    	cancel();
        executor.shutdownNow();
    }
}