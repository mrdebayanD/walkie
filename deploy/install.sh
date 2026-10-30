#!/usr/bin/env bash
# Installs Walkie + Caddy on a fresh Ubuntu server (e.g. Oracle Cloud Always Free).
# Usage:  sudo ./install.sh YOURNAME.duckdns.org ./walkie-linux-arm64
set -euo pipefail
DOMAIN="${1:?usage: sudo ./install.sh YOURNAME.duckdns.org path/to/walkie-binary}"
BIN="${2:?usage: sudo ./install.sh YOURNAME.duckdns.org path/to/walkie-binary}"
cd "$(dirname "$0")"

echo "==> Installing Caddy (HTTPS reverse proxy)"
apt-get update -y
apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
apt-get update -y
apt-get install -y caddy

echo "==> Opening ports 80 and 443 in the OS firewall"
# Oracle's Ubuntu images block these with iptables even after you open them in the cloud console.
if command -v iptables >/dev/null; then
  iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
  iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
  if command -v netfilter-persistent >/dev/null; then netfilter-persistent save; fi
fi

echo "==> Installing walkie"
id walkie >/dev/null 2>&1 || useradd --system --home /var/lib/walkie --shell /usr/sbin/nologin walkie
install -m 0755 "$BIN" /usr/local/bin/walkie
install -d -o walkie -g walkie -m 0700 /var/lib/walkie
install -d -o root -g walkie -m 0750 /etc/walkie
if [ ! -f /etc/walkie/walkie.env ]; then
  sed "s/YOURNAME.duckdns.org/$DOMAIN/" walkie.env.example > /etc/walkie/walkie.env
  chown root:walkie /etc/walkie/walkie.env
  chmod 0640 /etc/walkie/walkie.env
fi
install -m 0644 walkie.service /etc/systemd/system/walkie.service
sed "s/YOURNAME.duckdns.org/$DOMAIN/" Caddyfile > /etc/caddy/Caddyfile

systemctl daemon-reload
systemctl enable --now walkie
systemctl restart caddy

echo
echo "==> Done. Checking https://$DOMAIN/healthz (the certificate can take a minute):"
sleep 5
curl -fsS "https://$DOMAIN/healthz" && echo " <- server is up"
echo
echo "Your admin invite link (open it on YOUR phone within 24 hours):"
sudo -u walkie cat /var/lib/walkie/ADMIN_INVITE.txt | head -1
