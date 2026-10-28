package com.epam.learn.microservices.resourceservice.controller;

import com.epam.learn.microservices.resourceservice.client.SongServiceClient;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import com.epam.learn.microservices.resourceservice.service.SyntheticMp3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full chain: Spring MVC -> ResourceService -> Tika/frame scanner -> PostgreSQL (resource_test schema).
 * Shows that unsupported Content-Types and empty bodies reach the service validation and nothing is stored.
 * Only the Song Service client is mocked, so no running Song Service is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceHttpIntegrationTest {

    private static final String INVALID_MP3 = "Invalid file format: content is not a valid MP3. Only MP3 files are allowed";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResourceRepository resourceRepository;

    @MockitoBean
    private SongServiceClient songServiceClient;

    @BeforeEach
    void cleanDatabase() {
        resourceRepository.deleteAllInBatch();
    }

    private static byte[] validMp3() {
        return SyntheticMp3.builder()
                .id3v2("TIT2", "Test Title").id3v2("TPE1", "Test Artist").id3v2("TALB", "Test Album")
                .id3v2("TYER", "2025").audioFrames(299).build();
    }

    private static MockHttpServletRequestBuilder upload(String contentType, byte[] body) {
        var request = post("/resources").content(body);
        return contentType == null ? request : request.header(HttpHeaders.CONTENT_TYPE, contentType);
    }

    private ResultActions expectError(MockHttpServletRequestBuilder request, String message) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().json("""
                        {"errorMessage": "%s", "errorCode": "400"}""".formatted(message), JsonCompareMode.STRICT));
    }

    @ParameterizedTest(name = "Content-Type [{0}] -> {1}")
    @CsvSource(delimiter = '|', value = {
            "application/json                | application/json",
            "application/json; charset=UTF-8 | application/json",
            "text/plain                      | text/plain",
            "application/octet-stream        | application/octet-stream",
            "audio/mp3                       | audio/mp3",
    })
    void nonMp3ContentTypeIsRejectedWithSpecMessage(String contentType, String reported) throws Exception {
        expectError(upload(contentType, validMp3()),
                "Invalid file format: " + reported + ". Only MP3 files are allowed");
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void specCaseJsonBodyWithJsonContentType() throws Exception {
        expectError(upload("application/json", "invalid data".getBytes(StandardCharsets.UTF_8)),
                "Invalid file format: application/json. Only MP3 files are allowed");
    }

    @Test
    void missingContentTypeIsReportedAsUnknown() throws Exception {
        expectError(upload(null, validMp3()), "Invalid file format: unknown. Only MP3 files are allowed");
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void emptyBodyIsInvalidMp3() throws Exception {
        expectError(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg"), INVALID_MP3);
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void textWithMp3ContentTypeIsInvalidMp3() throws Exception {
        expectError(upload("audio/mpeg", "invalid data".getBytes(StandardCharsets.UTF_8)), INVALID_MP3);
    }

    @Test
    void id3TagWithoutAudioIsInvalidMp3() throws Exception {
        byte[] id3Only = SyntheticMp3.builder().id3v2("TIT2", "Test Title").audioFrames(0).build();

        expectError(upload("audio/mpeg", id3Only), INVALID_MP3);
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void malformedId3TagIsInvalidMp3NotServerError() throws Exception {
        expectError(upload("audio/mpeg", SyntheticMp3.withId3v2Flags(3, 0x02, -1, 299)), INVALID_MP3);
        expectError(upload("audio/mpeg", SyntheticMp3.withId3v2SizeReducedBy(validMp3(), 8)), INVALID_MP3);
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void contentTypeIsCheckedBeforeContent() throws Exception {
        expectError(upload("text/plain", "invalid data".getBytes(StandardCharsets.UTF_8)),
                "Invalid file format: text/plain. Only MP3 files are allowed");
    }

    @Test
    void mediaTypeComparisonIsCaseInsensitiveAndIgnoresParameters() throws Exception {
        mvc.perform(upload("AUDIO/MPEG", validMp3())).andExpect(status().isOk());
        mvc.perform(upload("audio/mpeg; foo=bar", validMp3())).andExpect(status().isOk());
        assertThat(resourceRepository.count()).isEqualTo(2);
    }

    @Test
    void uploadThenGetReturnsIdenticalBytesThenDeleteThenNotFound() throws Exception {
        byte[] mp3 = validMp3();

        String created = mvc.perform(upload("audio/mpeg", mp3))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll("\\D", ""));
        assertThat(created).isEqualTo("{\"id\":" + id + "}");
        assertThat(id).isPositive();

        mvc.perform(get("/resources/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(mp3.length)))
                .andExpect(content().bytes(mp3));

        mvc.perform(delete("/resources").param("id", id + ",101,102"))
                .andExpect(content().json("{\"ids\": [" + id + "]}", JsonCompareMode.STRICT));

        mvc.perform(get("/resources/" + id))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"errorMessage": "Resource with ID=%d not found", "errorCode": "404"}""".formatted(id),
                        JsonCompareMode.STRICT));
    }
}
