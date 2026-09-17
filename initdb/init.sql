CREATE DATABASE mellorator WITH ENCODING 'UTF8';

\c mellorator

-- The users of our app. There are no passwords: recoveryhash is a SHA-256 hash of a
-- machine-generated recovery phrase (see fivedomains.database.Auth), shown to the user once at
-- registration and used only to link a new device to this account.
CREATE TABLE IF NOT EXISTS melluser (
    id UUID PRIMARY KEY,
    name VARCHAR NOT NULL,
    recoveryhash VARCHAR NOT NULL UNIQUE,
    created BIGINT NOT NULL
);

-- Bearer tokens for day-to-day API auth, kept in the client's localStorage. Separate from the
-- recovery phrase so a device's token can't be used to derive it, and so registering a new
-- device just adds a token rather than invalidating existing ones.
CREATE TABLE IF NOT EXISTS usertoken (
    token VARCHAR PRIMARY KEY,
    melluser UUID NOT NULL REFERENCES melluser(id),
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
