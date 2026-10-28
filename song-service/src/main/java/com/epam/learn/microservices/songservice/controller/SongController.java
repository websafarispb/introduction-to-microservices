package com.epam.learn.microservices.songservice.controller;

import com.epam.learn.microservices.songservice.dto.SongRequest;
import com.epam.learn.microservices.songservice.dto.SongResponse;
import com.epam.learn.microservices.songservice.service.SongService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/songs")
public class SongController {

    private final SongService songService;

    public SongController(SongService songService) {
        this.songService = songService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@Valid @RequestBody SongRequest request) {
        return ResponseEntity.ok(Map.of("id", songService.create(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SongResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(songService.get(id));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, List<Long>>> delete(@RequestParam(name = "id", required = false) String id) {
        return ResponseEntity.ok(Map.of("ids", songService.delete(id)));
    }
}
