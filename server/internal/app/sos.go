package app

import (
	"context"
	"math"
	"net/http"
	"sort"
	"sync"
	"time"
)

const (
	sosRepeatEvery = 30 * time.Second
	sosMaxRepeats  = 10
)

type SOSView struct {
	SOS
	UserName string `json:"userName"`
}

func (s *Server) sosView(st *state, x *SOS) SOSView {
	v := SOSView{SOS: *x}
	v.Recipients = append([]string{}, x.Recipients...)
	v.Acked = append([]string{}, x.Acked...)
	v.Responders = append([]string{}, x.Responders...)
	v.ClipIDs = append([]string{}, x.ClipIDs...)
	if u := st.Users[x.UserID]; u != nil {
		v.UserName = u.Name
	} else {
		v.UserName = "Removed"
	}
	return v
}

func (s *Server) broadcastSOS(id string) {
	var v SOSView
	var to []string
	found := false
	s.store.View(func(st *state) {
		x := st.SOS[id]
		if x == nil {
			return
		}
		found = true
		v = s.sosView(st, x)
		to = append(append([]string{}, x.Recipients...), x.UserID)
	})
	if found {
		s.hub.SendTo(to, map[string]any{"t": "sos", "sos": v})
	}
}

func validLocation(lat, lng, acc float64) bool {
	return !math.IsNaN(lat) && !math.IsNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && acc >= 0 && acc < 1e6
}

func (s *Server) handleCreateSOS(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Lat      *float64 `json:"lat"`
		Lng      *float64 `json:"lng"`
		Accuracy *float64 `json:"accuracy"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	var v SOSView
	var created bool
	err := s.store.Update(func(st *state) error {
		for _, x := range st.SOS {
			if x.UserID == u.ID && x.State == "active" {
				v = s.sosView(st, x) // already active: return it, don't duplicate
				return nil
			}
		}
		def := st.defaultChannel()
		x := &SOS{ID: newID(), UserID: u.ID, ChannelID: def.ID, CreatedAt: time.Now().UTC(), State: "active",
			Recipients: []string{}, Acked: []string{}, Responders: []string{}, ClipIDs: []string{}}
		for _, m := range def.Members {
			if m != u.ID {
				if mu := st.Users[m]; mu != nil && mu.Status == StatusActive {
					x.Recipients = append(x.Recipients, m)
				}
			}
		}
		if req.Lat != nil && req.Lng != nil {
			acc := 0.0
			if req.Accuracy != nil {
				acc = *req.Accuracy
			}
			if validLocation(*req.Lat, *req.Lng, acc) {
				x.Location = &Location{Lat: *req.Lat, Lng: *req.Lng, Accuracy: acc, At: time.Now().UTC()}
			}
		}
		st.SOS[x.ID] = x
		v = s.sosView(st, x)
		created = true
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "Could not save")
		return
	}
	if created {
		s.logf("SOS %s from %s", v.ID, u.ID)
		s.broadcastSOS(v.ID)
		s.sosRep.start(v.ID)
	}
	writeJSON(w, http.StatusOK, map[string]any{"sos": v})
}

func (s *Server) handleListSOS(w http.ResponseWriter, r *http.Request, u User) {
	out := []SOSView{}
	s.store.View(func(st *state) {
		for _, x := range st.SOS {
			if x.UserID == u.ID || contains(x.Recipients, u.ID) || u.Role == RoleAdmin {
				out = append(out, s.sosView(st, x))
			}
		}
	})
	sort.Slice(out, func(i, j int) bool {
		if (out[i].State == "active") != (out[j].State == "active") {
			return out[i].State == "active"
		}
		return out[i].CreatedAt.After(out[j].CreatedAt)
	})
	writeJSON(w, http.StatusOK, map[string]any{"sos": out})
}

// mutateSOS loads an SOS, checks access, applies fn, saves and broadcasts.
func (s *Server) mutateSOS(w http.ResponseWriter, r *http.Request, u User, check func(x *SOS) (int, string), fn func(x *SOS)) {
	id := r.PathValue("id")
	var v SOSView
	status, msg := 0, ""
	err := s.store.Update(func(st *state) error {
		x := st.SOS[id]
		if x == nil {
			status, msg = http.StatusNotFound, "SOS not found"
			return ErrNotFound
		}
		if status, msg = check(x); status != 0 {
			return ErrNotFound
		}
		fn(x)
		v = s.sosView(st, x)
		return nil
	})
	if err != nil {
		if status == 0 {
			status, msg = http.StatusInternalServerError, "Could not save"
		}
		writeErr(w, status, "sos_error", msg)
		return
	}
	s.broadcastSOS(id)
	writeJSON(w, http.StatusOK, map[string]any{"sos": v})
}

func (s *Server) handleSOSLocation(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Lat      float64 `json:"lat"`
		Lng      float64 `json:"lng"`
		Accuracy float64 `json:"accuracy"`
	}
	if err := readJSON(r, &req); err != nil || !validLocation(req.Lat, req.Lng, req.Accuracy) {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid location")
		return
	}
	s.mutateSOS(w, r, u, func(x *SOS) (int, string) {
		if x.UserID != u.ID {
			return http.StatusForbidden, "Only the sender can update the location"
		}
		if x.State != "active" {
			return http.StatusConflict, "This SOS is over"
		}
		return 0, ""
	}, func(x *SOS) {
		x.Location = &Location{Lat: req.Lat, Lng: req.Lng, Accuracy: req.Accuracy, At: time.Now().UTC()}
	})
}

func (s *Server) handleSOSAck(w http.ResponseWriter, r *http.Request, u User) {
	s.mutateSOS(w, r, u, func(x *SOS) (int, string) {
		if !contains(x.Recipients, u.ID) {
			return http.StatusForbidden, "Not a recipient"
		}
		return 0, ""
	}, func(x *SOS) { x.Acked = addUnique(x.Acked, u.ID) })
}

func (s *Server) handleSOSRespond(w http.ResponseWriter, r *http.Request, u User) {
	s.mutateSOS(w, r, u, func(x *SOS) (int, string) {
		if !contains(x.Recipients, u.ID) {
			return http.StatusForbidden, "Not a recipient"
		}
		return 0, ""
	}, func(x *SOS) {
		x.Acked = addUnique(x.Acked, u.ID)
		x.Responders = addUnique(x.Responders, u.ID)
	})
}

func (s *Server) handleSOSResolve(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Resolution string `json:"resolution"`
	}
	if err := readJSON(r, &req); err != nil || (req.Resolution != "safe" && req.Resolution != "false_alarm") {
		writeErr(w, http.StatusBadRequest, "bad_request", "resolution must be safe or false_alarm")
		return
	}
	var resolved *SOS
	s.mutateSOS(w, r, u, func(x *SOS) (int, string) {
		if x.UserID != u.ID && u.Role != RoleAdmin {
			return http.StatusForbidden, "Only the sender or the admin can close an SOS"
		}
		if x.State != "active" {
			return http.StatusConflict, "Already closed"
		}
		return 0, ""
	}, func(x *SOS) {
		now := time.Now().UTC()
		x.State = "resolved"
		x.Resolution = req.Resolution
		x.ResolvedAt = &now
		x.ResolvedBy = u.ID
		c := *x
		resolved = &c
	})
	if resolved != nil {
		s.sosRep.stop(resolved.ID)
		var sender string
		var targets []User
		s.store.View(func(st *state) {
			if su := st.Users[resolved.UserID]; su != nil {
				sender = su.Name
			}
			for _, id := range resolved.Recipients {
				if x := st.Users[id]; x != nil {
					targets = append(targets, *x)
				}
			}
		})
		for _, t := range targets {
			s.sendPush(t, PushMessage{Kind: PushSOSResolved, SOSID: resolved.ID, Speaker: sender, Resolution: resolved.Resolution})
		}
	}
}

func (s *Server) handleDeleteSOS(w http.ResponseWriter, r *http.Request, u User) {
	id := r.PathValue("id")
	err := s.store.Update(func(st *state) error {
		x := st.SOS[id]
		if x == nil {
			return ErrNotFound
		}
		for _, cid := range x.ClipIDs {
			delete(st.Clips, cid)
			s.clips.Delete(cid)
		}
		delete(st.SOS, id)
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "SOS not found")
		return
	}
	s.sosRep.stop(id)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- repeating alerts until each recipient acknowledges ----

type sosRepeater struct {
	s    *Server
	mu   sync.Mutex
	sent map[string]int       // sosID -> rounds sent
	next map[string]time.Time // sosID -> next round
	kick chan struct{}
}

func newSOSRepeater(s *Server) *sosRepeater {
	return &sosRepeater{s: s, sent: map[string]int{}, next: map[string]time.Time{}, kick: make(chan struct{}, 1)}
}

func (r *sosRepeater) start(id string) {
	r.mu.Lock()
	r.sent[id] = 0
	r.next[id] = time.Now()
	r.mu.Unlock()
	select {
	case r.kick <- struct{}{}:
	default:
	}
}

func (r *sosRepeater) stop(id string) {
	r.mu.Lock()
	delete(r.sent, id)
	delete(r.next, id)
	r.mu.Unlock()
}

func (r *sosRepeater) loop(ctx context.Context) {
	// Resume repeats for SOS that were active when the server restarted.
	r.s.store.View(func(st *state) {
		for id, x := range st.SOS {
			if x.State == "active" && time.Since(x.CreatedAt) < sosRepeatEvery*sosMaxRepeats {
				r.mu.Lock()
				r.sent[id] = 0
				r.next[id] = time.Now()
				r.mu.Unlock()
			}
		}
	})
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		case <-r.kick:
		}
		r.tick()
	}
}

func (r *sosRepeater) tick() {
	now := time.Now()
	var due []string
	r.mu.Lock()
	for id, at := range r.next {
		if !now.Before(at) {
			due = append(due, id)
		}
	}
	r.mu.Unlock()
	for _, id := range due {
		var targets []User
		var sender string
		active := false
		r.s.store.View(func(st *state) {
			x := st.SOS[id]
			if x == nil || x.State != "active" {
				return
			}
			active = true
			if su := st.Users[x.UserID]; su != nil {
				sender = su.Name
			}
			for _, rid := range x.Recipients {
				if contains(x.Acked, rid) {
					continue
				}
				if u := st.Users[rid]; u != nil {
					targets = append(targets, *u)
				}
			}
		})
		r.mu.Lock()
		rounds := r.sent[id] + 1
		if !active || len(targets) == 0 || rounds > sosMaxRepeats {
			delete(r.sent, id)
			delete(r.next, id)
			r.mu.Unlock()
			continue
		}
		r.sent[id] = rounds
		r.next[id] = now.Add(sosRepeatEvery)
		r.mu.Unlock()
		for _, u := range targets {
			r.s.sendPush(u, PushMessage{Kind: PushSOS, SOSID: id, Speaker: sender, Repeat: rounds > 1})
		}
	}
}
