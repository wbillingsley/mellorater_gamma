CREATE DATABASE mellorator WITH ENCODING 'UTF8';

\c mellorator

-- The users of our app
CREATE TABLE IF NOT EXISTS melluser (
    id UUID PRIMARY KEY,
    name VARCHAR NOT NULL,
    created BIGINT NOT NULL
);

-- Animals belong to a user. The record itself is stored as JSON (data) so its shape can evolve
-- without a schema migration each time; id/owner/timestamps are pulled out as real columns
-- because those are what we actually query and join on.
CREATE TABLE IF NOT EXISTS animal (
    id UUID PRIMARY KEY,
    owner UUID NOT NULL REFERENCES melluser(id),
    data JSONB NOT NULL,
    created BIGINT NOT NULL,
    lastupdate BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS animal_owner ON animal(owner);

-- Survey responses about an animal. Also stored as JSON (data), since the answers map is
-- already free-form JSON on the client -- see common/shared/.../model/Assessment.scala.
CREATE TABLE IF NOT EXISTS assessment (
    id UUID PRIMARY KEY,
    animal UUID NOT NULL REFERENCES animal(id),
    owner UUID NOT NULL REFERENCES melluser(id),
    data JSONB NOT NULL,
    created BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS assessment_animal ON assessment(animal);
CREATE INDEX IF NOT EXISTS assessment_owner ON assessment(owner);
