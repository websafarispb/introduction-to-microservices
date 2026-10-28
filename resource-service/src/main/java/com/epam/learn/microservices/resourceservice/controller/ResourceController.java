package com.epam.learn.microservices.resourceservice.controller;

import com.epam.learn.microservices.resourceservice.service.ResourceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/resources")
public class ResourceController {

    private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    /**
     * No {@code consumes} restriction: any Content-Type reaches the service, which rejects
     * everything except audio/mpeg with the contract's 400 message (instead of Spring's 415).
     */
    @PostMapping
    public ResponseEntity<Map<String, Long>> upload(
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestBody(required = false) byte[] content) {
        return ResponseEntity.ok(Map.of("id", resourceService.upload(contentType, content)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        byte[] data = resourceService.get(id);
        return ResponseEntity.ok().contentType(AUDIO_MPEG).contentLength(data.length).body(data);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, List<Long>>> delete(@RequestParam(name = "id", required = false) String id) {
        return ResponseEntity.ok(Map.of("ids", resourceService.delete(id)));
    }
}
