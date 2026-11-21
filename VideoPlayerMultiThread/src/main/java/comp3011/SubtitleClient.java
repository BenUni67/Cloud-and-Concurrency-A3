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
    private static final long CHUNK_DURATION_US = 20_000_000L;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "subtitle-client");
                thread.setDaemon(true);
                return thread;
            });

    // starts transcription without blocking the videos playback
    public void start(File video, Consumer<List<SubtitleInstance>> oninstances, Consumer<String> onError) {
        executor.submit(() -> processAudio(video, oninstances, onError));
    }

    private void processAudio(File video, Consumer<List<SubtitleInstance>> oninstances, Consumer<String> onError) {
        Path tempFile = null;
        FFmpegFrameRecorder recorder = null;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();

            if (!grabber.hasAudio()) {
                onError.accept("Video contains no audio.");
                return;
            }

            long chunkStartUs = 0;
            Frame frame;

            try {
                while (!Thread.currentThread().isInterrupted() && (frame = grabber.grabSamples()) != null) {
                    if (frame.samples == null) {
                        continue;
                    }

                    long timestampUs = Math.max(0, grabber.getTimestamp());

                    // a finished chunk is now able to be transcribed
                    if (recorder != null && timestampUs - chunkStartUs >= CHUNK_DURATION_US) {
                        recorder.stop();
                        recorder.close();
                        recorder = null;

                        sendChunk(tempFile, chunkStartUs, oninstances, onError);
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

                    sendChunk(tempFile, chunkStartUs, oninstances, onError);
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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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

    // converts whisper segment timestamps into video timestamps
    private List<SubtitleInstance> parseinstances(JsonNode json, long offsetUs) {
        List<SubtitleInstance> instances = new ArrayList<>();
        JsonNode segments = json.path("segments");

        if (!segments.isArray()) {
            throw new IllegalArgumentException("Subtitle response has no segments array.");
        }

        for (JsonNode segment : segments) {
        	// A3 silence filtering requirement
            double noSpeechProb = segment.path("no_speech_prob").asDouble(0);
            double avgLogProb = segment.path("avg_logprob").asDouble(0);

            if (noSpeechProb > 0.6 && avgLogProb < -1.0) {
                continue;
            }

            String text = segment.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }

            long startUs = offsetUs + Math.round(segment.path("start").asDouble() * 1_000_000);
            long endUs = offsetUs + Math.round(segment.path("end").asDouble() * 1_000_000);
            if (endUs <= startUs) {
                continue;
            }
            instances.add(new SubtitleInstance(startUs, endUs, text));
        }
        return instances;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}