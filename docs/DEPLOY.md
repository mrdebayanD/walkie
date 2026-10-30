# Running the server for free

The server needs very little: about 10 MB of memory when idle, almost no CPU, and a few kilobytes per second while someone talks. Any small Linux machine with a public address works. This guide uses **Oracle Cloud Always Free** (Mumbai or Hyderabad region, close to Agartala) plus a free **DuckDNS** name and free automatic HTTPS.

## Before you start: two things to know about Oracle's free tier

1. **Sign-up asks for a phone number and a credit card.** Oracle says the card is not charged unless you upgrade the account ([Oracle Free Tier docs](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm)).
2. **Oracle may reclaim "idle" free servers.** A server counts as idle if, over 7 days, its CPU (95th percentile) and network use stay under 20% (and memory under 20% on Arm "A1" servers) ([Always Free resources](https://docs.oracle.com/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)). A family walkie-talkie server *is* idle almost all the time, so this can happen.

   Your options:
   - **Upgrade the account to Pay As You Go** (recommended). The same Always Free resources stay free, and the idle-reclaim rule is aimed at free-only accounts. The catch: if you ever create something outside the free limits, it is billed. Set a **budget alert of $1** (Billing › Budgets) so you'd know immediately.
   - **Stay free-only** and add a free uptime monitor (for example UptimeRobot on `https://YOURNAME.duckdns.org/healthz`) so you get an email if the server is stopped, then start it again in the console.

   Also note: in June 2026 Oracle quietly cut the free Arm allowance to 2 OCPUs and 12 GB total ([InfoQ](https://infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). Walkie needs far less, but keep any Arm server at **1 OCPU / 6 GB or less**.

## 1. Create the server (about 15 minutes)

1. Sign up at [oracle.com/cloud/free](https://www.oracle.com/cloud/free/). Pick **India South (Hyderabad)** or **India West (Mumbai)** as the home region; it can't be changed later.
2. Compute › Instances › **Create instance**:
   - Image: **Ubuntu 24.04** (or 22.04).
   - Shape: **VM.Standard.A1.Flex** with **1 OCPU, 6 GB** (Arm), or **VM.Standard.E2.1.Micro** (AMD, 1 GB) if Arm says "out of capacity".
   - Networking: keep "Assign a public IPv4 address". Download the SSH key it offers.
3. On the instance page, under the VNIC, make the public IP **Reserved** so it never changes (optional but recommended).
4. Networking › your VCN › Security List › **Add ingress rules**: TCP port **80** and TCP port **443** from `0.0.0.0/0`.

## 2. Get a free name and point it at the server

1. Go to [duckdns.org](https://www.duckdns.org) and sign in (Google or GitHub).
2. Create a subdomain, e.g. `dattafamily` → `dattafamily.duckdns.org`.
3. Put the server's public IP in the "current ip" box and click **update ip**.

## 3. Build and install

On your Mac (needs Go: `brew install go`):

```bash
cd server
make linux-arm64      # or: make linux-amd64 for the E2.1.Micro shape
scp -i ~/Downloads/ssh-key.key walkie-linux-arm64 ../deploy/* ubuntu@SERVER_IP:~/
```

On the server:

```bash
ssh -i ~/Downloads/ssh-key.key ubuntu@SERVER_IP
sudo ./install.sh dattafamily.duckdns.org ./walkie-linux-arm64
```

The script installs Caddy (automatic HTTPS), opens the OS firewall (Oracle's Ubuntu blocks 80/443 even after step 1.4), creates a locked-down `walkie` system user, and starts everything. At the end it prints **your admin invite link**. Open it on your iPhone after installing the app (it's valid for 24 hours; if it expires, run `sudo systemctl restart walkie` and read the new link with `sudo cat /var/lib/walkie/ADMIN_INVITE.txt`).

Check it any time: `https://dattafamily.duckdns.org/healthz` should say `ok`.

## 4. Turn on push (wakes sleeping phones)

Edit `/etc/walkie/walkie.env` (`sudo nano /etc/walkie/walkie.env`), then `sudo systemctl restart walkie`.

**iPhone (APNs)** — needs your paid Apple Developer membership:

1. [developer.apple.com/account](https://developer.apple.com/account) › Certificates, IDs & Profiles › **Keys** › **+** › tick **Apple Push Notifications service (APNs)** › Continue › Register › **Download** the `AuthKey_XXXXXXXXXX.p8` (only downloadable once).
2. Copy it to the server: `scp AuthKey_*.p8 ubuntu@SERVER_IP:~/` then `sudo install -o root -g walkie -m 0640 ~/AuthKey_*.p8 /etc/walkie/`.
3. In `walkie.env` uncomment and fill `WALKIE_APNS_KEY_FILE`, `WALKIE_APNS_KEY_ID` (the 10 characters in the file name), `WALKIE_APNS_TEAM_ID` (top right of the developer site), `WALKIE_APNS_BUNDLE_ID` (same as in `ios/project.yml`).

**Android (Firebase Cloud Messaging, free)**:

1. [console.firebase.google.com](https://console.firebase.google.com) › Add project (Analytics off) › Add app › Android › package `com.debayandatta.walkie` › download `google-services.json` into `android/app/` (needed when building the app).
2. Project settings › **Service accounts** › **Generate new private key** › copy that JSON to the server: `sudo install -o root -g walkie -m 0640 ~/your-key.json /etc/walkie/firebase-service-account.json`.
3. In `walkie.env` uncomment `WALKIE_FCM_SERVICE_ACCOUNT`.

## Updating the server

```bash
make linux-arm64 && scp walkie-linux-arm64 ubuntu@SERVER_IP:~/
ssh ubuntu@SERVER_IP 'sudo install -m 0755 walkie-linux-arm64 /usr/local/bin/walkie && sudo systemctl restart walkie'
```

Data (users, channels, history) lives in `/var/lib/walkie` and survives updates. To back it up: `sudo tar czf walkie-backup.tgz -C /var/lib walkie`.

## Logs and troubleshooting

| Symptom | Check |
| --- | --- |
| `healthz` doesn't load | `sudo systemctl status walkie caddy`, `sudo journalctl -u caddy -n 50` (certificate problems are usually ports 80/443 not open) |
| iPhones don't wake when locked | `sudo journalctl -u walkie | grep apns` — "BadDeviceToken" means a Debug (Xcode) build is talking to production APNs or vice versa; see docs/IOS_SETUP.md |
| Android doesn't wake | `journalctl -u walkie | grep fcm`; also see the battery settings in docs/ANDROID_SETUP.md |
| Server was stopped by Oracle | Start it again in the console; consider the Pay As You Go note above |
