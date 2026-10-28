package com.epam.learn.microservices.resourceservice.controller;

import com.epam.learn.microservices.resourceservice.entity.Resource;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import com.epam.learn.microservices.resourceservice.service.SyntheticMp3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real load-balanced Song Service client with no {@code song-service} instance known (Eureka is
 * disabled in the test profile and no simple-discovery instance is configured): uploads and deletes answer
 * with the generic 500 and the local change is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceWithoutSongInstanceTest {

    private static final String GENERIC_500 =
            "{\"errorMessage\":\"An internal server error has occurred\",\"errorCode\":\"500\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResourceRepository resourceRepository;

    @BeforeEach
    void cleanDatabase() {
        resourceRepository.deleteAllInBatch();
    }

    @Test
    void uploadIsRolledBack() throws Exception {
        byte[] mp3 = SyntheticMp3.builder()
                .id3v2("TIT2", "Test Title").id3v2("TPE1", "Test Artist").id3v2("TALB", "Test Album")
                .id3v2("TYER", "2025").audioFrames(299).build();

        mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg").content(mp3))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(GENERIC_500, true));

        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void deleteIsRolledBack() throws Exception {
        Long id = resourceRepository.save(new Resource(new byte[] {1, 2, 3})).getId();

        mvc.perform(delete("/resources").param("id", String.valueOf(id)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(GENERIC_500, true));

        assertThat(resourceRepository.existsById(id)).isTrue();
    }
}
