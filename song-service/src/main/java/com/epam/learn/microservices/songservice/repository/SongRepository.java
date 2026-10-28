package com.epam.learn.microservices.songservice.repository;

import com.epam.learn.microservices.songservice.entity.Song;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SongRepository extends JpaRepository<Song, Long> {
}
