package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Addr      string // listen address, e.g. 127.0.0.1:8080 (behind Caddy)
	DataDir   string
	PublicURL string // https://walkie.example.org — used in invite links

	APNs *APNsConfig // nil = no iPhone push
	FCM  *FCMConfig  // nil = no Android push
}

type Server struct {
	cfg    Config
	store  *Store
	hub    *Hub
	clips  *ClipStore
	push   Pusher
	limit  *rateLimiter
	mux    *http.ServeMux
	sosRep *sosRepeater
	logf   func(format string, args ...any)
}

func New(cfg Config, push Pusher) (*Server, error) {
	st, err := OpenStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	if push == nil {
		push = NewPusher(cfg.APNs, cfg.FCM)
	}
	s := &Server{
		cfg:   cfg,
		store: st,
		clips: NewClipStore(cfg.DataDir),
		push:  push,
		limit: newRateLimiter(10, time.Minute),
		mux:   http.NewServeMux(),
		logf:  log.Printf,
	}
	s.hub = NewHub(s)
	s.sosRep = newSOSRepeater(s)
	s.routes()
	return s, nil
}

func (s *Server) Store() *Store { return s.store }

func (s *Server) Handler() http.Handler { return s.withCommon(s.mux) }

// Run starts background jobs and serves HTTP until ctx is done.
func (s *Server) Run(ctx context.Context) error {
	go s.sweepLoop(ctx)
	go s.sosRep.loop(ctx)
	srv := &http.Server{
		Addr:              s.cfg.Addr,
		Handler:           s.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	s.logf("walkie listening on %s (public %s)", s.cfg.Addr, s.cfg.PublicURL)
	select {
	case <-ctx.Done():
		shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		s.hub.Close()
		return srv.Shutdown(shut)
	case err := <-errc:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (s *Server) routes() {
	m := s.mux
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "ok") })
	m.HandleFunc("GET /join", s.handleJoinPage)

	m.HandleFunc("POST /v1/join", s.handleJoin)
	m.HandleFunc("GET /v1/me", s.auth(false, false, s.handleMe))
	m.HandleFunc("PUT /v1/me", s.auth(false, false, s.handleUpdateMe))
	m.HandleFunc("PUT /v1/me/push", s.auth(false, false, s.handlePushTokens))

	m.HandleFunc("GET /v1/users", s.auth(true, false, s.handleUsers))
	m.HandleFunc("POST /v1/users/{id}/approve", s.auth(true, true, s.handleApprove))
	m.HandleFunc("DELETE /v1/users/{id}", s.auth(true, true, s.handleDeleteUser))
	m.HandleFunc("PUT /v1/users/{id}", s.auth(true, true, s.handleUpdateUser))

	m.HandleFunc("GET /v1/channels", s.auth(true, false, s.handleChannels))
	m.HandleFunc("POST /v1/channels", s.auth(true, true, s.handleCreateChannel))
	m.HandleFunc("PATCH /v1/channels/{id}", s.auth(true, true, s.handleUpdateChannel))
	m.HandleFunc("PUT /v1/channels/{id}/members", s.auth(true, true, s.handleSetMembers))
	m.HandleFunc("DELETE /v1/channels/{id}", s.auth(true, false, s.handleDeleteChannel)) // group: admin; personal: either member
	m.HandleFunc("POST /v1/direct", s.auth(true, false, s.handleDirect))
	m.HandleFunc("GET /v1/channels/{id}/clips", s.auth(true, false, s.handleClips))
	m.HandleFunc("GET /v1/clips/{id}/audio", s.auth(true, false, s.handleClipAudio))

	m.HandleFunc("POST /v1/invites", s.auth(true, true, s.handleCreateInvite))
	m.HandleFunc("GET /v1/invites", s.auth(true, true, s.handleListInvites))
	m.HandleFunc("DELETE /v1/invites/{id}", s.auth(true, true, s.handleDeleteInvite))

	m.HandleFunc("GET /v1/settings", s.auth(true, false, s.handleGetSettings))
	m.HandleFunc("PUT /v1/settings", s.auth(true, true, s.handlePutSettings))

	m.HandleFunc("POST /v1/sos", s.auth(true, false, s.handleCreateSOS))
	m.HandleFunc("GET /v1/sos", s.auth(true, false, s.handleListSOS))
	m.HandleFunc("POST /v1/sos/{id}/location", s.auth(true, false, s.handleSOSLocation))
	m.HandleFunc("POST /v1/sos/{id}/ack", s.auth(true, false, s.handleSOSAck))
	m.HandleFunc("POST /v1/sos/{id}/respond", s.auth(true, false, s.handleSOSRespond))
	m.HandleFunc("POST /v1/sos/{id}/resolve", s.auth(true, false, s.handleSOSResolve))
	m.HandleFunc("DELETE /v1/sos/{id}", s.auth(true, true, s.handleDeleteSOS))

	m.HandleFunc("GET /v1/ws", s.auth(true, false, s.handleWS))
}

// ---- middleware ----

type ctxKey int

const userKey ctxKey = 1

func (s *Server) withCommon(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		h.ServeHTTP(w, r)
	})
}

// auth checks the bearer token. needActive rejects pending users;
// needAdmin rejects non-admins.
func (s *Server) auth(needActive, needAdmin bool, h func(w http.ResponseWriter, r *http.Request, u User)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" || tok == r.Header.Get("Authorization") {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "Missing token")
			return
		}
		var u *User
		h1 := hashSecret(tok)
		s.store.View(func(st *state) {
			if found := st.userByTokenHash(h1); found != nil {
				c := *found
				u = &c
			}
		})
		if u == nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "Unknown or removed device")
			return
		}
		if needActive && u.Status != StatusActive {
			writeErr(w, http.StatusForbidden, "pending", "Waiting for the admin to approve you")
			return
		}
		if needAdmin && u.Role != RoleAdmin {
			writeErr(w, http.StatusForbidden, "forbidden", "Only the admin can do this")
			return
		}
		h(w, r, *u)
	}
}

// ---- JSON helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

func readJSON(r *http.Request, v any) error {
	dec := json.NewDecoder(io.LimitReader(r.Body, 64<<10))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// ---- rate limiting for unauthenticated endpoints ----

type rateLimiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	hits   map[string][]time.Time
}

func newRateLimiter(max int, window time.Duration) *rateLimiter {
	return &rateLimiter{max: max, window: window, hits: map[string][]time.Time{}}
}

func (rl *rateLimiter) allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now()
	h := rl.hits[key]
	kept := h[:0]
	for _, t := range h {
		if now.Sub(t) < rl.window {
			kept = append(kept, t)
		}
	}
	if len(kept) >= rl.max {
		rl.hits[key] = kept
		return false
	}
	rl.hits[key] = append(kept, now)
	if len(rl.hits) > 10000 { // bound memory
		rl.hits = map[string][]time.Time{}
	}
	return true
}

func clientIP(r *http.Request) string {
	// Caddy sets X-Forwarded-For; we only trust it when the peer is local.
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			return strings.TrimSpace(strings.Split(xff, ",")[0])
		}
	}
	return host
}
