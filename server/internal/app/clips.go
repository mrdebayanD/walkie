package app

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

const (
	clipMagic      = "WKC1"
	clipSampleRate = 48000
	clipFrameMs    = 20
)

type ClipStore struct{ dir string }

func NewClipStore(dataDir string) *ClipStore {
	return &ClipStore{dir: filepath.Join(dataDir, "clips")}
}

var clipIDRe = regexp.MustCompile(`^[0-9]{1,10}$`)

func (cs *ClipStore) path(id string) string { return filepath.Join(cs.dir, id+".wkc") }

func (cs *ClipStore) Delete(id string) {
	if clipIDRe.MatchString(id) {
		_ = os.Remove(cs.path(id))
	}
}

// ClipWriter appends Opus packets for one transmission.
type ClipWriter struct {
	f       *os.File
	w       *bufio.Writer
	packets int
	bytes   int64
}

func (cs *ClipStore) Create(id string) (*ClipWriter, error) {
	if !clipIDRe.MatchString(id) {
		return nil, errors.New("bad clip id")
	}
	f, err := os.OpenFile(cs.path(id), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, err
	}
	w := bufio.NewWriterSize(f, 16<<10)
	hdr := make([]byte, 10)
	copy(hdr, clipMagic)
	binary.BigEndian.PutUint32(hdr[4:], clipSampleRate)
	binary.BigEndian.PutUint16(hdr[8:], clipFrameMs)
	if _, err := w.Write(hdr); err != nil {
		f.Close()
		return nil, err
	}
	return &ClipWriter{f: f, w: w, bytes: 10}, nil
}

// Append writes length-prefixed packets (the payload after the 0x01 type byte).
func (cw *ClipWriter) Append(payload []byte, packets int) error {
	n, err := cw.w.Write(payload)
	cw.bytes += int64(n)
	cw.packets += packets
	return err
}

func (cw *ClipWriter) DurationMs() int64 { return int64(cw.packets * clipFrameMs) }

func (cw *ClipWriter) Close() error {
	if err := cw.w.Flush(); err != nil {
		cw.f.Close()
		return err
	}
	return cw.f.Close()
}

// countPackets validates a length-prefixed payload and returns how many
// packets it holds. Opus packets are at most 1275 bytes.
func countPackets(p []byte) (int, bool) {
	n := 0
	for len(p) > 0 {
		if len(p) < 2 {
			return 0, false
		}
		l := int(binary.BigEndian.Uint16(p))
		if l == 0 || l > 1275 || len(p) < 2+l {
			return 0, false
		}
		p = p[2+l:]
		n++
	}
	return n, n > 0
}

// ---- HTTP ----

type ClipView struct {
	ID         string     `json:"id"`
	ChannelID  string     `json:"channelId"`
	UserID     string     `json:"userId"`
	UserName   string     `json:"userName"`
	Mode       string     `json:"mode"`
	SOSID      string     `json:"sosId,omitempty"`
	StartedAt  time.Time  `json:"startedAt"`
	DurationMs int64      `json:"durationMs"`
	ExpiresAt  *time.Time `json:"expiresAt,omitempty"`
}

func (s *Server) handleClips(w http.ResponseWriter, r *http.Request, u User) {
	cid := r.PathValue("id")
	out := []ClipView{}
	allowed := false
	s.store.View(func(st *state) {
		c := st.Channels[cid]
		if c == nil || !c.canSee(u) {
			return
		}
		allowed = true
		ttl := time.Duration(st.Settings.ClipTTLHours) * time.Hour
		for _, cl := range st.Clips {
			if cl.ChannelID != cid {
				continue
			}
			v := ClipView{ID: cl.ID, ChannelID: cl.ChannelID, UserID: cl.UserID, Mode: cl.Mode, SOSID: cl.SOSID, StartedAt: cl.StartedAt, DurationMs: cl.DurationMs}
			if x := st.Users[cl.UserID]; x != nil {
				v.UserName = x.Name
			} else {
				v.UserName = "Removed"
			}
			if cl.SOSID == "" {
				e := cl.StartedAt.Add(ttl)
				v.ExpiresAt = &e
			}
			out = append(out, v)
		}
	})
	if !allowed {
		writeErr(w, http.StatusNotFound, "not_found", "Channel not found")
		return
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.After(out[j].StartedAt) })
	if len(out) > 500 {
		out = out[:500]
	}
	writeJSON(w, http.StatusOK, map[string]any{"clips": out})
}

func (s *Server) handleClipAudio(w http.ResponseWriter, r *http.Request, u User) {
	id := r.PathValue("id")
	allowed := false
	s.store.View(func(st *state) {
		cl := st.Clips[id]
		if cl == nil {
			return
		}
		c := st.Channels[cl.ChannelID]
		if c != nil && c.canSee(u) {
			allowed = true
		}
		if cl.SOSID != "" {
			if sos := st.SOS[cl.SOSID]; sos != nil && contains(sos.Recipients, u.ID) {
				allowed = true
			}
		}
	})
	if !allowed || !clipIDRe.MatchString(id) {
		writeErr(w, http.StatusNotFound, "not_found", "Clip not found")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	http.ServeFile(w, r, s.clips.path(id))
}

// ---- TTL sweeper ----

func (s *Server) sweepLoop(ctx context.Context) {
	s.sweepOnce()
	t := time.NewTicker(10 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.sweepOnce()
		}
	}
}

func (s *Server) sweepOnce() {
	now := time.Now()
	_ = s.store.Update(func(st *state) error {
		ttl := time.Duration(st.Settings.ClipTTLHours) * time.Hour
		for id, cl := range st.Clips {
			if cl.SOSID != "" {
				continue // SOS clips stay until the admin deletes the SOS entry
			}
			if now.Sub(cl.StartedAt) > ttl {
				delete(st.Clips, id)
				s.clips.Delete(id)
			}
		}
		for id, inv := range st.Invites {
			if now.After(inv.ExpiresAt.Add(24 * time.Hour)) {
				delete(st.Invites, id)
			}
		}
		return nil
	})
	// Remove orphaned files (e.g. a crash mid-write).
	entries, _ := os.ReadDir(s.clips.dir)
	var known map[string]bool
	s.store.View(func(st *state) {
		known = make(map[string]bool, len(st.Clips))
		for id := range st.Clips {
			known[id] = true
		}
	})
	active := s.hub.ActiveTxIDs()
	for _, e := range entries {
		name := e.Name()
		id := name[:len(name)-len(filepath.Ext(name))]
		if !known[id] && !active[id] {
			if info, err := e.Info(); err == nil && now.Sub(info.ModTime()) > time.Hour {
				_ = os.Remove(filepath.Join(s.clips.dir, name))
			}
		}
	}
}
