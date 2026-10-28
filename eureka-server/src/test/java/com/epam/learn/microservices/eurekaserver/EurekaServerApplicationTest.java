package com.epam.learn.microservices.eurekaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the registry with the Spring Cloud compatibility verifier explicitly enabled, so an
 * incompatible Spring Boot / Spring Cloud combination fails this test at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.compatibility-verifier.enabled=true")
class EurekaServerApplicationTest {

    @Autowired
    private EurekaClientConfigBean clientConfig;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void runsAsStandaloneRegistry() {
        assertThat(clientConfig.shouldRegisterWithEureka()).isFalse();
        assertThat(clientConfig.shouldFetchRegistry()).isFalse();
    }

    @Test
    void registryApiAnswersWithNoApplications() throws Exception {
        var headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
                "/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode applications = new ObjectMapper().readTree(response.getBody()).path("applications").path("application");
        assertThat(applications.isArray()).isTrue();
        assertThat(applications).isEmpty();
    }
}
