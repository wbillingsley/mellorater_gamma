#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu (24.04) box for animalwellbeing, behind Caddy.
# Run as root on the server, from a directory containing: awServer.jar, dist/, init.sql,
# awserver.service, awserver.env.example, Caddyfile. See deploy/README.md.
#
#   sudo ./setup-ubuntu.sh your.domain.example
#
# Safe to re-run: steps that are already done are skipped.
set -euo pipefail

DOMAIN="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
APP=/opt/animalwellbeing
ENV_FILE=/etc/sysconfig/awserver

# Extra swap as a safety net for the JVM + Postgres on a 2 GB box.
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-17-jre-headless postgresql debian-keyring debian-archive-keyring apt-transport-https curl gnupg

# Caddy from its official apt repo (https://caddyserver.com/docs/install#debian-ubuntu-raspbian).
if [ ! -f /etc/apt/sources.list.d/caddy-stable.list ]; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
  apt-get update
fi
apt-get install -y caddy

id awserver >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin awserver
mkdir -p "$APP"

# Env file, with a generated DB password on first run. AI keys are left for the operator to fill in.
mkdir -p /etc/sysconfig
if [ ! -f "$ENV_FILE" ]; then
  PGPW="$(openssl rand -hex 24)"
  sed -e "s|^PGPASSWORD=.*|PGPASSWORD=$PGPW|" \
      -e "s|^CORS_ALLOW_ORIGIN=.*|CORS_ALLOW_ORIGIN=https://$DOMAIN|" \
      -e "s|^GROQ_API_KEY=.*|GROQ_API_KEY=|" \
      "$HERE/awserver.env.example" > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
fi
PGPW="$(grep '^PGPASSWORD=' "$ENV_FILE" | cut -d= -f2-)"

# Database: schema from init.sql (as postgres, so postgres owns the tables), then an app role
# that can read/write those tables but not change the schema. Ubuntu's Postgres already allows
# password logins over 127.0.0.1, so no pg_hba.conf change is needed (unlike RHEL).
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='mellorator'" | grep -q 1; then
  # via stdin, since the postgres user may not be able to read $HERE (e.g. under /root)
  sudo -u postgres psql -v ON_ERROR_STOP=1 < "$HERE/init.sql"
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 -d mellorator <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'awserver') THEN
    CREATE ROLE awserver LOGIN;
  END IF;
END \$\$;
ALTER ROLE awserver PASSWORD '$PGPW';
GRANT CONNECT ON DATABASE mellorator TO awserver;
GRANT USAGE ON SCHEMA public TO awserver;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO awserver;
SQL

# App: jar + built client, run by systemd.
cp "$HERE/awServer.jar" "$APP/"
rm -rf "$APP/dist" && cp -r "$HERE/dist" "$APP/dist"
chown -R awserver:awserver "$APP"
cp "$HERE/awserver.service" /etc/systemd/system/awserver.service
systemctl daemon-reload
systemctl enable awserver
systemctl restart awserver

# Caddy: TLS termination + reverse proxy to the app.
sed "s|example.com|$DOMAIN|" "$HERE/Caddyfile" > /etc/caddy/Caddyfile
systemctl enable caddy
systemctl reload-or-restart caddy

# Firewall: only SSH and HTTP(S) from outside -- in particular, the app's own port 8081 is not
# exposed directly (it listens on 0.0.0.0).
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "Done. Check: curl http://127.0.0.1:8081/api/health && curl -I https://$DOMAIN/mellorater-alpha/"
