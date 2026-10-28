package com.epam.learn.microservices.songservice.controller;

import com.epam.learn.microservices.songservice.dto.SongRequest;
import com.epam.learn.microservices.songservice.dto.SongResponse;
import com.epam.learn.microservices.songservice.exception.InvalidIdException;
import com.epam.learn.microservices.songservice.exception.SongAlreadyExistsException;
import com.epam.learn.microservices.songservice.exception.SongNotFoundException;
import com.epam.learn.microservices.songservice.service.SongService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T5: HTTP contract of SongController and GlobalExceptionHandler (service mocked, no database).
 */
@WebMvcTest(SongController.class)
class SongControllerTest {

    private static final String VALID_BODY = """
            {"id": 1, "name": "We are the champions", "artist": "Queen",
             "album": "News of the world", "duration": "02:59", "year": "1977"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SongService songService;

    private ResultActions postSong(String body) throws Exception {
        return mvc.perform(post("/songs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void expectJson(ResultActions result, int status, String json) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().json(json, JsonCompareMode.STRICT));
    }

    // --- POST /songs ---

    @Test
    void createReturnsId() throws Exception {
        when(songService.create(any())).thenReturn(1L);

        expectJson(postSong(VALID_BODY), 200, """
                {"id": 1}""");
    }

    @Test
    void allEmptyFieldsReturnFormatMessagesWithoutIdWord() throws Exception {
        var result = postSong("""
                {"id": 102, "name": "", "artist": "", "album": "", "duration": "", "year": ""}""");

        expectJson(result, 400, """
                {"errorMessage": "Validation error",
                 "details": {"duration": "Duration must be in mm:ss format with leading zeros",
                             "year": "Year must be between 1900 and 2099",
                             "artist": "Artist name must be between 1 and 100 characters",
                             "album": "Album name must be between 1 and 100 characters",
                             "name": "Song name must be between 1 and 100 characters"},
                 "errorCode": "400"}""");
        result.andExpect(content().string(not(matchesPattern("(?s).*\\bid\\b.*"))));
        verifyNoInteractions(songService);
    }

    @Test
    void missingFieldsReturnRequiredMessages() throws Exception {
        expectJson(postSong("""
                {"id": 102}"""), 400, """
                {"errorMessage": "Validation error",
                 "details": {"duration": "Duration is required", "artist": "Artist name is required",
                             "year": "Year is required", "album": "Album name is required",
                             "name": "Song name is required"},
                 "errorCode": "400"}""");
    }

    @Test
    void invalidDurationAndYearReportOnlyThoseFields() throws Exception {
        expectJson(postSong("""
                {"id": 102, "name": "We are the champions", "artist": "Queen",
                 "album": "News of the world", "duration": "02:77", "year": "01977"}"""), 400, """
                {"errorMessage": "Validation error",
                 "details": {"duration": "Duration must be in mm:ss format with leading zeros",
                             "year": "Year must be between 1900 and 2099"},
                 "errorCode": "400"}""");
    }

    @Test
    void duplicateIdReturnsConflict() throws Exception {
        when(songService.create(any())).thenThrow(new SongAlreadyExistsException(2L, null));

        expectJson(postSong(VALID_BODY), 409, """
                {"errorMessage": "Metadata for resource ID=2 already exists", "errorCode": "409"}""");
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        expectJson(postSong("{\"id\": 1,"), 400, """
                {"errorMessage": "Invalid request body", "errorCode": "400"}""");
    }

    @Test
    void nonIntegerIdInBodyIsBadRequest() throws Exception {
        expectJson(postSong(VALID_BODY.replace("\"id\": 1", "\"id\": 1.5")), 400, """
                {"errorMessage": "Invalid request body", "errorCode": "400"}""");
        expectJson(postSong(VALID_BODY.replace("\"id\": 1", "\"id\": \"abc\"")), 400, """
                {"errorMessage": "Invalid request body", "errorCode": "400"}""");
        verifyNoInteractions(songService);
    }

    @Test
    void unsupportedContentTypeKeeps415() throws Exception {
        var result = mvc.perform(post("/songs").contentType(MediaType.TEXT_PLAIN).content("x"));

        expectJson(result, 415, """
                {"errorMessage": "Unsupported Media Type", "errorCode": "415"}""");
    }

    // --- GET /songs/{id} ---

    @Test
    void getReturnsExactlySixFields() throws Exception {
        when(songService.get("1")).thenReturn(
                new SongResponse(1L, "Test Title", "Test Artist", "Test Album", "00:07", "2025"));

        expectJson(mvc.perform(get("/songs/1")), 200, """
                {"id": 1, "name": "Test Title", "artist": "Test Artist", "album": "Test Album",
                 "duration": "00:07", "year": "2025"}""");
    }

    @Test
    void unknownSongIsNotFound() throws Exception {
        when(songService.get("99999")).thenThrow(new SongNotFoundException(99999L));

        expectJson(mvc.perform(get("/songs/99999")), 404, """
                {"errorMessage": "Song metadata for ID=99999 not found", "errorCode": "404"}""");
    }

    @Test
    void errorIsJsonEvenWhenClientAcceptsOnlyNonJsonTypes() throws Exception {
        // Regression: with Accept: audio/mpeg or text/plain the JSON error could not be written and the
        // request ended as an empty 500 instead of the 404 body.
        when(songService.get("99999")).thenThrow(new SongNotFoundException(99999L));

        for (String accept : List.of("audio/mpeg", "text/plain")) {
            expectJson(mvc.perform(get("/songs/99999").header(HttpHeaders.ACCEPT, accept)), 404, """
                    {"errorMessage": "Song metadata for ID=99999 not found", "errorCode": "404"}""");
        }
    }

    @Test
    void validationErrorIsJsonEvenWhenClientAcceptsOnlyText() throws Exception {
        var result = mvc.perform(post("/songs").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "text/plain").content("""
                        {"id": 103, "artist": "Queen", "album": "News of the world", "duration": "02:59", "year": "1977"}"""));

        expectJson(result, 400, """
                {"errorMessage": "Validation error", "details": {"name": "Song name is required"}, "errorCode": "400"}""");
    }

    @Test
    void frameworkErrorIsJsonEvenWhenClientAcceptsOnlyText() throws Exception {
        expectJson(mvc.perform(put("/songs/1").header(HttpHeaders.ACCEPT, "text/plain")), 405, """
                {"errorMessage": "Method Not Allowed", "errorCode": "405"}""");
    }

    @Test
    void invalidIdIsBadRequestAndRawValueIsPassedToService() throws Exception {
        when(songService.get("1.1")).thenThrow(
                new InvalidIdException("Invalid value '1.1' for ID. Must be a positive integer"));

        expectJson(mvc.perform(get("/songs/1.1")), 400, """
                {"errorMessage": "Invalid value '1.1' for ID. Must be a positive integer", "errorCode": "400"}""");
    }

    @Test
    void unsupportedMethodKeeps405WithAllowHeader() throws Exception {
        var result = mvc.perform(put("/songs/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        expectJson(result, 405, """
                {"errorMessage": "Method Not Allowed", "errorCode": "405"}""");
        result.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    @Test
    void unexpectedErrorIs500WithoutInternalDetails() throws Exception {
        when(songService.get("1")).thenThrow(new IllegalStateException("secret internal detail"));

        var result = mvc.perform(get("/songs/1"));

        expectJson(result, 500, """
                {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""");
        result.andExpect(content().string(not(containsString("secret"))));
    }

    // --- DELETE /songs?id= ---

    @Test
    void deleteReturnsDeletedIds() throws Exception {
        when(songService.delete("1,101,2")).thenReturn(List.of(1L, 2L));

        expectJson(mvc.perform(delete("/songs").param("id", "1,101,2")), 200, """
                {"ids": [1, 2]}""");
    }

    @Test
    void deleteWithoutParameterPassesNullToService() throws Exception {
        when(songService.delete(null)).thenThrow(
                new InvalidIdException("Invalid ID format: ''. Only positive integers are allowed"));

        expectJson(mvc.perform(delete("/songs")), 400, """
                {"errorMessage": "Invalid ID format: ''. Only positive integers are allowed", "errorCode": "400"}""");
        verify(songService).delete(null);
    }

    @Test
    void validRequestIsPassedToServiceUnchanged() throws Exception {
        when(songService.create(any())).thenReturn(1L);

        postSong(VALID_BODY);

        verify(songService).create(new SongRequest(1L, "We are the champions", "Queen",
                "News of the world", "02:59", "1977"));
    }
}
