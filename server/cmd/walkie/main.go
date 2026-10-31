// Command walkie runs the family walkie-talkie server.
//
//	walkie serve   run the server (reads settings from environment variables)
//	walkie setup   create a one-time admin invite link (stop the server first)
//
// Environment:
//
//	WALKIE_ADDR            listen address (default 127.0.0.1:8080; Caddy sits in front)
//	WALKIE_DATA            data folder (default ./data)
//	WALKIE_PUBLIC_URL      https URL people reach, e.g. https://myfamily.duckdns.org
//	WALKIE_APNS_KEY_FILE   AuthKey_XXXXXXXXXX.p8 from Apple (optional)
//	WALKIE_APNS_KEY_ID     the key's 10-character ID
//	WALKIE_APNS_TEAM_ID    your Apple team ID
//	WALKIE_APNS_BUNDLE_ID  the iPhone app's bundle ID
//	WALKIE_APNS_CRITICAL   1 if Apple granted the Critical Alerts entitlement
//	WALKIE_FCM_SERVICE_ACCOUNT  Firebase service-account JSON (optional)
//	WALKIE_ANDROID_APK     path to walkie.apk, offered on invite pages (optional)
//	WALKIE_TESTFLIGHT_URL  public TestFlight link, offered on invite pages (optional)
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"walkie/server/internal/app"
)

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

func config() app.Config {
	cfg := app.Config{
		Addr:          env("WALKIE_ADDR", "127.0.0.1:8080"),
		DataDir:       env("WALKIE_DATA", "./data"),
		PublicURL:     strings.TrimRight(env("WALKIE_PUBLIC_URL", "http://localhost:8080"), "/"),
		AndroidAPK:    env("WALKIE_ANDROID_APK", ""),
		TestFlightURL: env("WALKIE_TESTFLIGHT_URL", ""),
	}
	if f := env("WALKIE_APNS_KEY_FILE", ""); f != "" {
		cfg.APNs = &app.APNsConfig{
			KeyFile:  f,
			KeyID:    env("WALKIE_APNS_KEY_ID", ""),
			TeamID:   env("WALKIE_APNS_TEAM_ID", ""),
			BundleID: env("WALKIE_APNS_BUNDLE_ID", ""),
			Critical: env("WALKIE_APNS_CRITICAL", "") == "1",
		}
		if cfg.APNs.KeyID == "" || cfg.APNs.TeamID == "" || cfg.APNs.BundleID == "" {
			log.Fatal("WALKIE_APNS_KEY_ID, WALKIE_APNS_TEAM_ID and WALKIE_APNS_BUNDLE_ID are required with WALKIE_APNS_KEY_FILE")
		}
	}
	if f := env("WALKIE_FCM_SERVICE_ACCOUNT", ""); f != "" {
		cfg.FCM = &app.FCMConfig{ServiceAccountFile: f}
	}
	return cfg
}

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	cfg := config()
	switch cmd {
	case "serve":
		s, err := app.New(cfg, nil)
		if err != nil {
			log.Fatal(err)
		}
		if link, created, err := s.EnsureAdminInvite(); err != nil {
			log.Fatal(err)
		} else if created {
			log.Printf("No admin yet. Open this link on your phone within 24 hours to become the admin:\n\n    %s\n", link)
		}
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if err := s.Run(ctx); err != nil {
			log.Fatal(err)
		}
	case "setup":
		s, err := app.New(cfg, nil)
		if err != nil {
			log.Fatal(err)
		}
		code, link, appLink, err := s.CreateAdminInvite()
		if err != nil {
			log.Fatal(err)
		}
		fmt.Printf("Admin invite (valid 24 hours, one use)\n\n  Link: %s\n  App link: %s\n  Code: %s\n\n", link, appLink, code)
	case "version":
		fmt.Println(app.Version)
	default:
		fmt.Fprintf(os.Stderr, "usage: walkie [serve|setup|version]\n")
		os.Exit(2)
	}
}
