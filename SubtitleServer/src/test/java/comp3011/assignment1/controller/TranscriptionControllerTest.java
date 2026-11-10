package comp3011.subtitleserver.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MultipartFile;

import comp3011.subtitleserver.dto.TranscriptionResponse;
import comp3011.subtitleserver.service.TranscriptionService;

@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void transcriptionReturnsExpectedResponse() throws Exception {
    	
    	// Builds a fake upload file to simulate the multipart request
    	// Contains dummy byte data
        MockMultipartFile audio = new MockMultipartFile("file", "recording.webm", "audio/webm", new byte[] { 1, 2, 3, 4 });

        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn(CompletableFuture.completedFuture(new TranscriptionResponse("Hello world", 10, 3, 13)));

        MvcResult result = mockMvc.perform(multipart("/api/v1/transcriptions").file(audio))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Hello world"))
                .andExpect(jsonPath("$.inputTokens").value(10))
                .andExpect(jsonPath("$.outputTokens").value(3))
                .andExpect(jsonPath("$.totalTokens").value(13));
    }
}
