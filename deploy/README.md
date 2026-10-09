# Deploying

The server process (`awServer.jar`) serves both the API and the built client (see Main.scala's
`/mellorater-alpha` route, backed by the `CLIENT_DIST_DIR` env var) — it's the only backend.
A reverse proxy sits in front purely to terminate TLS: haproxy on the university's RHEL server,
or Caddy on a standalone Ubuntu box (e.g. Linode), which gets its own Let's Encrypt cert.

A 2 GB box is enough (the JVM heap is capped at 512 MB in `awserver.service`). The AI feedback
runs at Anthropic/Groq, not locally.

## Build

On a machine with sbt/node, e.g. the devcontainer — not the server itself:

```sh
sbt awServer/assembly   # -> server/target/scala-3.5.2/awServer.jar
npm run build           # -> dist/
```

## Linode (Ubuntu + Caddy)

Point the domain's DNS A record at the server first (Caddy needs it to get a certificate), then
ship everything to the server and run the setup script, which installs Java/Postgres/Caddy,
creates the database and app role (with a generated password), installs and starts the app, and
enables the firewall (SSH/80/443 only). It's safe to re-run.

```sh
ssh root@myserver 'mkdir -p /root/aw-deploy'
scp -r server/target/scala-3.5.2/awServer.jar dist initdb/init.sql deploy/awserver.service \
    deploy/awserver.env.example deploy/Caddyfile deploy/setup-ubuntu.sh root@myserver:/root/aw-deploy/
ssh root@myserver '/root/aw-deploy/setup-ubuntu.sh mellorater.example.com'
```

Then add an AI key — `ANTHROPIC_API_KEY` and/or `GROQ_API_KEY` in `/etc/sysconfig/awserver` —
and `systemctl restart awserver`. Everything except AI feedback works without one.

## University server (RHEL + haproxy)

One-time server setup:

```sh
sudo dnf install java-17-openjdk-headless haproxy postgresql-server
sudo postgresql-setup --initdb
```

RHEL's Postgres defaults to `ident` auth for TCP connections, which refuses the app's password
login over 127.0.0.1. In `/var/lib/pgsql/data/pg_hba.conf`, change the method on the `host ...
127.0.0.1/32` and `host ... ::1/128` lines from `ident` to `scram-sha-256`, then:

```sh
sudo systemctl enable --now postgresql
sudo useradd -r -s /sbin/nologin awserver
sudo mkdir -p /opt/animalwellbeing
```

Create the database and app role (adjust the password, matches `PGPASSWORD` below). The tables
are owned by `postgres`, so the app role needs table privileges granted explicitly — a grant on
the database alone isn't enough:

```sh
sudo -u postgres psql < initdb/init.sql
sudo -u postgres psql -d mellorator <<'SQL'
CREATE ROLE awserver LOGIN PASSWORD 'changeme';
GRANT USAGE ON SCHEMA public TO awserver;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO awserver;
SQL
```

Ship and install:

```sh
scp server/target/scala-3.5.2/awServer.jar myserver:/opt/animalwellbeing/
scp -r dist myserver:/opt/animalwellbeing/
scp deploy/awserver.service myserver:/tmp/
scp deploy/awserver.env.example myserver:/tmp/awserver.env
scp deploy/haproxy.cfg myserver:/tmp/

# on the RHEL server:
sudo mv /tmp/awserver.service /etc/systemd/system/
sudo mv /tmp/awserver.env /etc/sysconfig/awserver && sudo chmod 600 /etc/sysconfig/awserver
sudo vi /etc/sysconfig/awserver   # fill in PGPASSWORD, ANTHROPIC_API_KEY and/or GROQ_API_KEY
sudo chown -R awserver:awserver /opt/animalwellbeing

sudo mv /tmp/haproxy.cfg /etc/haproxy/haproxy.cfg
# get a cert, combine it into the PEM haproxy expects, and point haproxy.cfg at it --
# see the comment block at the top of deploy/haproxy.cfg for the exact commands.

sudo systemctl daemon-reload
sudo systemctl enable --now awserver
sudo setsebool -P haproxy_connect_any 1
sudo firewall-cmd --add-service=http --add-service=https --permanent && sudo firewall-cmd --reload
sudo systemctl enable --now haproxy
```

## Verify and update

`curl http://127.0.0.1:8081/api/health` on the server should return `ok` directly from the jar,
and once the proxy/DNS/cert are in place, `https://<domain>/mellorater-alpha/` should load the app.

To deploy an update later: rebuild, copy the jar and `dist/` into `/opt/animalwellbeing/`
(`chown -R awserver:awserver` it), then `sudo systemctl restart awserver`.
