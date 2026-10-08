# Deploying to a RHEL server

The server process (`awServer.jar`) serves both the API and the built client (see Main.scala's
`/mellorater-alpha` route, backed by the `CLIENT_DIST_DIR` env var) — it's the only backend.
haproxy sits in front purely to terminate TLS.

One-time server setup:

```sh
sudo dnf install java-17-openjdk-headless haproxy postgresql-server
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
sudo useradd -r -s /sbin/nologin awserver
sudo mkdir -p /opt/animalwellbeing
```

Create the database and app role (adjust the password, matches `PGPASSWORD` below):

```sh
sudo -u postgres psql -f initdb/init.sql
sudo -u postgres psql -c "CREATE USER awserver WITH PASSWORD 'changeme';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE mellorator TO awserver;"
```

Build (on a machine with sbt/node, e.g. the devcontainer — not necessarily the RHEL box itself):

```sh
sbt awServer/assembly   # -> server/target/scala-3.5.2/awServer.jar
npm run build           # -> dist/
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

Verify: `curl http://127.0.0.1:8081/api/health` on the server should return `ok` directly from
the jar, and once haproxy/DNS/cert are in place, `https://example.com/mellorater-alpha/` should
load the app.

To deploy an update later: rebuild, re-`scp` the jar and `dist/`, then
`sudo systemctl restart awserver`.
