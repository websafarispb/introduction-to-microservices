-- Table for the Song entity. The id is the Resource ID, so it is not generated here.

CREATE TABLE song (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    artist       VARCHAR(100) NOT NULL,
    album        VARCHAR(100) NOT NULL,
    duration     VARCHAR(5)   NOT NULL,
    release_year VARCHAR(4)   NOT NULL
);
