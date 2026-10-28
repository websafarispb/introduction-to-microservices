package com.epam.learn.microservices.resourceservice.repository;

import com.epam.learn.microservices.resourceservice.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    /** Returns which of the given ids exist, without loading the audio data. */
    @Query("select r.id from Resource r where r.id in :ids")
    List<Long> findExistingIds(Collection<Long> ids);
}
