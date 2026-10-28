package com.epam.learn.microservices.resourceservice.dto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization with the application's own Jackson configuration (the one the auto-configured
 * RestClient.Builder uses): every field is written, absent tags as null, in contract field order.
 */
@JsonTest
class SongMetadataRequestJsonTest {

    @Autowired
    private JacksonTester<SongMetadataRequest> json;

    @Test
    void allFieldsAreWrittenIncludingNulls() throws Exception {
        var request = new SongMetadataRequest(7L, "Valid Title", "", null, "00:07", "2025");

        assertThat(json.write(request).getJson()).isEqualTo(
                "{\"id\":7,\"name\":\"Valid Title\",\"artist\":\"\",\"album\":null,"
                        + "\"duration\":\"00:07\",\"year\":\"2025\"}");
    }
}
