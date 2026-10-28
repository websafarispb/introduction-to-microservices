package com.epam.learn.microservices.resourceservice.exception;

/**
 * The Song Service did not confirm the deletion of the metadata of deleted resources. The local delete is
 * rolled back. Always a server-side failure (HTTP 500), whatever the Song Service answered.
 */
public class MetadataDeletionException extends RuntimeException {

    public MetadataDeletionException(String message) {
        super(message);
    }
}
