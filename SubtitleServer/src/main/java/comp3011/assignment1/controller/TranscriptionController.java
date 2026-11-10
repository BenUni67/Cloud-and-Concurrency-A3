package comp3011.subtitleserver.controller;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.subtitleserver.dto.TranscriptionResponse;
import comp3011.subtitleserver.service.TranscriptionService;

@RestController
@RequestMapping("/api/v1/transcriptions")
public class TranscriptionController {

    // Sends the audio to OpenAi
    private final TranscriptionService transcriptionService;


    // Spring gives the controller the TranscriptionService automatically
    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }



     // Accepts a POST request containing an uploaded file

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<TranscriptionResponse> transcribe(@RequestParam MultipartFile file) throws IOException {

    	// Gives audio file to TranscriptionService which sends it to OpenAi and returns a transcription
        return transcriptionService.transcribe(file);
    }
}
