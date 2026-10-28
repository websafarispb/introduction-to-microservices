package com.epam.learn.microservices.songservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Song metadata. The id is the Resource ID supplied by the caller, not generated here.
 *
 * <p>Implements {@link Persistable} so that {@code save()} always INSERTs a new song
 * instead of silently updating an existing row with the same id.
 */
@Entity
@Table(name = "song")
public class Song implements Persistable<Long> {

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String artist;

    @Column(nullable = false, length = 100)
    private String album;

    @Column(nullable = false, length = 5)
    private String duration;

    @Column(name = "release_year", nullable = false, length = 4)
    private String year;

    @Transient
    private boolean isNew = true;

    protected Song() {
        // required by JPA
    }

    public Song(Long id, String name, String artist, String album, String duration, String year) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.year = year;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public String getName() {
        return name;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getDuration() {
        return duration;
    }

    public String getYear() {
        return year;
    }
}
