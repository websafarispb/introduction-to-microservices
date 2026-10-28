package com.epam.learn.microservices.resourceservice.controller;

import com.epam.learn.microservices.resourceservice.exception.InvalidFileFormatException;
import com.epam.learn.microservices.resourceservice.exception.InvalidIdException;
import com.epam.learn.microservices.resourceservice.exception.MetadataDeletionException;
import com.epam.learn.microservices.resourceservice.exception.ResourceNotFoundException;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import com.epam.learn.microservices.resourceservice.service.ResourceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T5: HTTP contract of ResourceController and GlobalExceptionHandler (service mocked, no database).
 *
 * <p>Note: MockMvc appends {@code ;charset=UTF-8} to the request Content-Type header, so the service
 * receives e.g. {@code audio/mpeg;charset=UTF-8} here; real HTTP clients' headers arrive unchanged
 * (verified against the running application). Stubs therefore match on the media type prefix.
 */
@WebMvcTest(ResourceController.class)
class ResourceControllerTest {

    private static final byte[] AUDIO = {(byte) 0xFF, (byte) 0xFB, (byte) 0xB0, 0x00, 1, 2, 3};

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ResourceService resourceService;

    private static void expectJson(ResultActions result, int status, String json) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().json(json, JsonCompareMode.STRICT));
    }

    // --- POST /resources: header and body are passed through unchanged ---

    @Test
    void uploadReturnsIdAndPassesHeaderAndBodyToService() throws Exception {
        when(resourceService.upload(startsWith("audio/mpeg"), eq(AUDIO))).thenReturn(1L);

        expectJson(mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg").content(AUDIO)),
                200, """
                {"id": 1}""");
    }

    @Test
    void jsonContentTypeReachesServiceInsteadOfSpring415() throws Exception {
        when(resourceService.upload(startsWith("application/json"), any())).thenThrow(new InvalidFileFormatException(
                "Invalid file format: application/json. Only MP3 files are allowed"));

        var result = mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .content("invalid data"));

        expectJson(result, 400, """
                {"errorMessage": "Invalid file format: application/json. Only MP3 files are allowed", "errorCode": "400"}""");
        verify(resourceService).upload(startsWith("application/json"), eq("invalid data".getBytes()));
    }

    @Test
    void missingContentTypeIsPassedAsNull() throws Exception {
        when(resourceService.upload(isNull(), any())).thenThrow(
                new InvalidFileFormatException("Invalid file format: unknown. Only MP3 files are allowed"));

        expectJson(mvc.perform(post("/resources").content(AUDIO)), 400, """
                {"errorMessage": "Invalid file format: unknown. Only MP3 files are allowed", "errorCode": "400"}""");
    }

    @Test
    void emptyBodyReachesServiceAsNullOrEmpty() throws Exception {
        when(resourceService.upload(anyString(), any())).thenThrow(new InvalidFileFormatException("x"));

        mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(status().isBadRequest());

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(resourceService).upload(startsWith("audio/mpeg"), body.capture());
        assertThat(body.getValue()).satisfiesAnyOf(b -> assertThat(b).isNull(), b -> assertThat(b).isEmpty());
    }

    // --- GET /resources/{id} ---

    @Test
    void getReturnsAudioBytesWithExactHeaders() throws Exception {
        when(resourceService.get("1")).thenReturn(AUDIO);

        mvc.perform(get("/resources/1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(AUDIO.length)))
                .andExpect(content().bytes(AUDIO));
    }

    @Test
    void getIgnoresAcceptHeaderBecauseContentTypeIsSetExplicitly() throws Exception {
        // Observed behaviour (the expected 406 does not happen): a preset Content-Type skips negotiation.
        when(resourceService.get("1")).thenReturn(AUDIO);

        mvc.perform(get("/resources/1").header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"));
    }

    @Test
    void unknownResourceIsNotFound() throws Exception {
        when(resourceService.get("99999")).thenThrow(new ResourceNotFoundException(99999L));

        expectJson(mvc.perform(get("/resources/99999")), 404, """
                {"errorMessage": "Resource with ID=99999 not found", "errorCode": "404"}""");
    }

    @Test
    void errorIsJsonEvenWhenClientAcceptsOnlyAudio() throws Exception {
        // Regression: without an explicit JSON Content-Type this ended as an empty 500.
        when(resourceService.get("99999")).thenThrow(new ResourceNotFoundException(99999L));

        expectJson(mvc.perform(get("/resources/99999").header(HttpHeaders.ACCEPT, "audio/mpeg")), 404, """
                {"errorMessage": "Resource with ID=99999 not found", "errorCode": "404"}""");
    }

    @Test
    void invalidIdIsBadRequest() throws Exception {
        when(resourceService.get("1.1")).thenThrow(
                new InvalidIdException("Invalid value '1.1' for ID. Must be a positive integer"));

        expectJson(mvc.perform(get("/resources/1.1")), 400, """
                {"errorMessage": "Invalid value '1.1' for ID. Must be a positive integer", "errorCode": "400"}""");
    }

    // --- DELETE /resources?id= ---

    @Test
    void deleteReturnsDeletedIds() throws Exception {
        when(resourceService.delete("1,101,102")).thenReturn(List.of(1L));

        expectJson(mvc.perform(delete("/resources").param("id", "1,101,102")), 200, """
                {"ids": [1]}""");
    }

    @Test
    void deleteWithoutParameterPassesNull() throws Exception {
        when(resourceService.delete(null)).thenThrow(
                new InvalidIdException("Invalid ID format: ''. Only positive integers are allowed"));

        expectJson(mvc.perform(delete("/resources")), 400, """
                {"errorMessage": "Invalid ID format: ''. Only positive integers are allowed", "errorCode": "400"}""");
    }

    // --- Song Service failures (S4.2) ---

    @Test
    void downstreamValidationErrorIsPassedOnVerbatim() throws Exception {
        String body = "{\"errorMessage\":\"Validation error\",\"details\":{\"artist\":\"Artist name is required\"},"
                + "\"errorCode\":\"400\"}";
        when(resourceService.upload(any(), any())).thenThrow(
                new SongServiceException("Song Service failed: 400", HttpStatus.BAD_REQUEST, body, null));

        mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg").content(AUDIO))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().bytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void otherDownstreamFailuresAreGeneric500WithoutDetails() throws Exception {
        var failures = List.of(
                new SongServiceException("m", HttpStatus.BAD_REQUEST, "{\"unexpected\":true}", null),
                new SongServiceException("m", HttpStatus.CONFLICT, "{\"errorMessage\":\"exists\",\"errorCode\":\"409\"}", null),
                new SongServiceException("m", HttpStatus.SERVICE_UNAVAILABLE, "maintenance", null),
                new SongServiceException("outcome unknown", null, null, null));
        for (SongServiceException failure : failures) {
            doThrow(failure).when(resourceService).upload(any(), any()); // re-stubbing without calling the mock

            var result = mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg").content(AUDIO));

            expectJson(result, 500, """
                    {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""");
            result.andExpect(content().string(not(containsString("exists"))))
                    .andExpect(content().string(not(containsString("maintenance"))))
                    .andExpect(content().string(not(containsString("unexpected"))));
        }
    }

    @Test
    void deleteCascadeFailureIsGeneric500() throws Exception {
        when(resourceService.delete("1")).thenThrow(
                new MetadataDeletionException("Song Service did not confirm metadata deletion for resources [1]"));

        var result = mvc.perform(delete("/resources").param("id", "1"));

        expectJson(result, 500, """
                {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""");
        result.andExpect(content().string(not(containsString("did not confirm"))));
    }

    // --- framework errors keep their status, internal details stay hidden ---

    @Test
    void unsupportedMethodKeeps405() throws Exception {
        var result = mvc.perform(put("/resources/1"));

        expectJson(result, 405, """
                {"errorMessage": "Method Not Allowed", "errorCode": "405"}""");
        result.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    @Test
    void unexpectedErrorIs500WithoutInternalDetails() throws Exception {
        when(resourceService.get("1")).thenThrow(new IllegalStateException("secret internal detail"));

        var result = mvc.perform(get("/resources/1"));

        expectJson(result, 500, """
                {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""");
        result.andExpect(content().string(not(containsString("secret"))));
    }
}
