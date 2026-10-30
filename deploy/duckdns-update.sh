#!/usr/bin/env bash
# Keeps YOURNAME.duckdns.org pointing at this server. Run from cron every 5 minutes:
#   */5 * * * * /opt/walkie/duckdns-update.sh >/dev/null 2>&1
# Oracle "reserved public IPs" don't change, so this is only a safety net.
DOMAIN_NAME="YOURNAME"          # without .duckdns.org
TOKEN="YOUR-DUCKDNS-TOKEN"      # from https://www.duckdns.org after signing in
curl -fsS "https://www.duckdns.org/update?domains=${DOMAIN_NAME}&token=${TOKEN}&ip="
