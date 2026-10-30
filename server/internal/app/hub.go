package app

import (
	"encoding/binary"
	"encoding/json"
	"strconv"
	"sync"
	"time"
)

// Lock order (to avoid deadlocks):
//   hub.mu  ->  store lock  ->  hub.presMu
// Code holding the store lock may read presence, but must never take hub.mu.

const (
	limitHold      = 120 * time.Second
	limitHandsfree = 60 * time.Second
	limitSOS       = 15 * time.Second
	limitGrace     = 2 * time.Second
	idleTimeout    = 10 * time.Second
	catchupMax     = 512 << 10 // bytes of audio kept for late joiners
)

type outMsg struct {
	binary bool
	data   []byte
}

type conn struct {
	userID string
	name   string
	send   chan outMsg
	duty   bool // guarded by hub.mu
	closed bool // guarded by hub.mu
	kill   func()
}

type floor struct {
	channel   string
	userID    string
	name      string
	txID      uint32
	mode      string
	sosID     string
	started   time.Time
	deadline  time.Time
	lastAudio time.Time
	writer    *ClipWriter
	buffer    [][]byte // outgoing audio messages for late joiners
	bufBytes  int
	owner     *conn
}

type Hub struct {
	s      *Server
	mu     sync.Mutex
	conns  map[string]map[*conn]struct{}
	floors map[string]*floor
	closed bool
	stop   chan struct{}

	presMu   sync.RWMutex
	presence map[string][2]bool // userID -> {online, duty}

	pushMu     sync.Mutex
	lastPushed map[string]time.Time
}

func NewHub(s *Server) *Hub {
	h := &Hub{
		s:          s,
		conns:      map[string]map[*conn]struct{}{},
		floors:     map[string]*floor{},
		presence:   map[string][2]bool{},
		lastPushed: map[string]time.Time{},
		stop:       make(chan struct{}),
	}
	go h.watchdog()
	return h
}

func (h *Hub) Presence(userID string) (online, duty bool) {
	h.presMu.RLock()
	defer h.presMu.RUnlock()
	p := h.presence[userID]
	return p[0], p[1]
}

func encode(v any) outMsg {
	b, _ := json.Marshal(v)
	return outMsg{data: b}
}

// sendLocked queues a message; a client too slow to keep up is dropped.
func (h *Hub) sendLocked(c *conn, m outMsg) {
	if c.closed {
		return
	}
	select {
	case c.send <- m:
	default:
		h.s.logf("dropping slow connection for %s", c.userID)
		c.closed = true
		close(c.send)
		c.kill()
	}
}

func (h *Hub) userConnsLocked(userID string) []*conn {
	var out []*conn
	for c := range h.conns[userID] {
		if !c.closed {
			out = append(out, c)
		}
	}
	return out
}

// refreshPresenceLocked recomputes a user's presence and tells co-members.
func (h *Hub) refreshPresenceLocked(userID string) {
	online, duty := false, false
	for _, c := range h.userConnsLocked(userID) {
		online = true
		if c.duty {
			duty = true
		}
	}
	h.presMu.Lock()
	prev := h.presence[userID]
	h.presence[userID] = [2]bool{online, duty}
	h.presMu.Unlock()
	if prev == [2]bool{online, duty} {
		return
	}
	msg := encode(map[string]any{"t": "presence", "userId": userID, "online": online, "duty": duty})
	for _, uid := range h.coMembers(userID) {
		for _, c := range h.userConnsLocked(uid) {
			h.sendLocked(c, msg)
		}
	}
}

// coMembers returns everyone sharing at least one channel with userID.
func (h *Hub) coMembers(userID string) []string {
	seen := map[string]bool{}
	var out []string
	h.s.store.View(func(st *state) {
		for _, c := range st.Channels {
			if !contains(c.Members, userID) {
				continue
			}
			for _, m := range c.Members {
				if m != userID && !seen[m] {
					seen[m] = true
					out = append(out, m)
				}
			}
		}
	})
	return out
}

func (h *Hub) channelMembers(channelID string) (members []string, ok bool) {
	h.s.store.View(func(st *state) {
		if c := st.Channels[channelID]; c != nil {
			members = append([]string(nil), c.Members...)
			ok = true
		}
	})
	return
}

// register adds a connection after hello and returns active floors to replay.
func (h *Hub) register(c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		c.kill()
		return
	}
	if h.conns[c.userID] == nil {
		h.conns[c.userID] = map[*conn]struct{}{}
	}
	h.conns[c.userID][c] = struct{}{}

	active := []map[string]any{}
	var replay []*floor
	for _, f := range h.floors {
		members, _ := h.channelMembers(f.channel)
		if contains(members, c.userID) {
			active = append(active, map[string]any{"channel": f.channel, "txId": f.txID, "userId": f.userID, "startedAt": f.started})
			replay = append(replay, f)
		}
	}
	h.sendLocked(c, encode(map[string]any{"t": "welcome", "userId": c.userID, "active": active}))
	for _, f := range replay {
		h.sendLocked(c, encode(floorTakenMsg(f)))
		if c.duty && f.userID != c.userID {
			for _, m := range f.buffer {
				h.sendLocked(c, outMsg{binary: true, data: m})
			}
		}
	}
	h.refreshPresenceLocked(c.userID)
}

func (h *Hub) unregister(c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if set := h.conns[c.userID]; set != nil {
		delete(set, c)
		if len(set) == 0 {
			delete(h.conns, c.userID)
		}
	}
	if !c.closed {
		c.closed = true
		close(c.send)
	}
	for ch, f := range h.floors {
		if f.owner == c {
			h.endFloorLocked(ch, "disconnected")
		}
	}
	h.refreshPresenceLocked(c.userID)
}

func (h *Hub) setDuty(c *conn, on bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c.duty = on
	h.refreshPresenceLocked(c.userID)
}

func floorTakenMsg(f *floor) map[string]any {
	return map[string]any{"t": "floor_taken", "channel": f.channel, "txId": f.txID, "userId": f.userID, "name": f.name, "mode": f.mode, "startedAt": f.started}
}

func (h *Hub) requestFloor(c *conn, channel, mode, sosID string) {
	limit := map[string]time.Duration{"hold": limitHold, "handsfree": limitHandsfree, "sos": limitSOS}[mode]
	if limit == 0 {
		h.mu.Lock()
		h.sendLocked(c, encode(map[string]any{"t": "floor_denied", "channel": channel, "reason": "bad_request"}))
		h.mu.Unlock()
		return
	}
	members, ok := h.channelMembers(channel)
	if mode == "sos" {
		// The SOS clip always goes to the default channel the SOS was sent to.
		valid := false
		h.s.store.View(func(st *state) {
			if sos := st.SOS[sosID]; sos != nil && sos.UserID == c.userID && sos.State == "active" && sos.ChannelID == channel {
				valid = true
			}
		})
		if !valid {
			ok = false
		}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if !ok || !contains(members, c.userID) {
		h.sendLocked(c, encode(map[string]any{"t": "floor_denied", "channel": channel, "reason": "not_member"}))
		return
	}
	// A user talks on one channel at a time.
	for ch, f := range h.floors {
		if f.owner == c && ch != channel {
			h.endFloorLocked(ch, "released")
		}
	}
	if f := h.floors[channel]; f != nil {
		if f.owner == c {
			// Already ours (e.g. a repeated request): re-confirm.
			h.sendLocked(c, encode(map[string]any{"t": "floor_granted", "channel": channel, "txId": f.txID, "maxMs": time.Until(f.deadline).Milliseconds()}))
			return
		}
		if mode != "sos" {
			h.sendLocked(c, encode(map[string]any{"t": "floor_denied", "channel": channel, "reason": "busy", "holder": f.userID}))
			return
		}
		h.endFloorLocked(channel, "preempted")
	}

	var txID uint32
	_ = h.s.store.Update(func(st *state) error { txID = st.takeTxID(); return nil })
	idStr := strconv.FormatUint(uint64(txID), 10)
	w, err := h.s.clips.Create(idStr)
	if err != nil {
		h.s.logf("clip create failed: %v", err)
		h.sendLocked(c, encode(map[string]any{"t": "error", "code": "storage", "message": "Server storage problem"}))
		return
	}
	now := time.Now()
	f := &floor{channel: channel, userID: c.userID, name: c.name, txID: txID, mode: mode, sosID: sosID,
		started: now.UTC(), deadline: now.Add(limit + limitGrace), lastAudio: now, writer: w, owner: c}
	h.floors[channel] = f
	h.sendLocked(c, encode(map[string]any{"t": "floor_granted", "channel": channel, "txId": txID, "maxMs": limit.Milliseconds()}))

	taken := encode(floorTakenMsg(f))
	var offline []string
	for _, uid := range members {
		if uid == c.userID {
			continue
		}
		cs := h.userConnsLocked(uid)
		if len(cs) == 0 {
			offline = append(offline, uid)
		}
		for _, oc := range cs {
			h.sendLocked(oc, taken)
		}
	}
	// Wake phones that are asleep (iPhone PTT push / Android FCM).
	go h.pushTx(offline, f.channel, txID, c.name)
}

func (h *Hub) pushTx(userIDs []string, channel string, txID uint32, speaker string) {
	if len(userIDs) == 0 {
		return
	}
	now := time.Now()
	var targets []User
	h.pushMu.Lock()
	h.s.store.View(func(st *state) {
		for _, id := range userIDs {
			u := st.Users[id]
			if u == nil || u.Status != StatusActive {
				continue
			}
			if t, ok := h.lastPushed[id]; ok && now.Sub(t) < 3*time.Second {
				continue
			}
			h.lastPushed[id] = now
			targets = append(targets, *u)
		}
	})
	h.pushMu.Unlock()
	for _, u := range targets {
		h.s.sendPush(u, PushMessage{Kind: PushTx, Channel: channel, TxID: txID, Speaker: speaker})
	}
}

func (h *Hub) releaseFloor(c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch, f := range h.floors {
		if f.owner == c {
			h.endFloorLocked(ch, "released")
		}
	}
}

func (h *Hub) audio(c *conn, data []byte) {
	if len(data) < 3 || data[0] != 0x01 {
		return
	}
	payload := data[1:]
	n, ok := countPackets(payload)
	if !ok || n > 10 {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	var f *floor
	for _, x := range h.floors {
		if x.owner == c {
			f = x
			break
		}
	}
	if f == nil {
		return // not holding the floor; ignore
	}
	f.lastAudio = time.Now()
	if err := f.writer.Append(payload, n); err != nil {
		h.s.logf("clip write failed: %v", err)
	}
	out := make([]byte, 5+len(payload))
	out[0] = 0x01
	binary.BigEndian.PutUint32(out[1:5], f.txID)
	copy(out[5:], payload)
	if f.bufBytes+len(out) <= catchupMax {
		f.buffer = append(f.buffer, out)
		f.bufBytes += len(out)
	}
	msg := outMsg{binary: true, data: out}
	members, _ := h.channelMembers(f.channel)
	for _, uid := range members {
		if uid == c.userID {
			continue
		}
		for _, oc := range h.userConnsLocked(uid) {
			if oc.duty {
				h.sendLocked(oc, msg)
			}
		}
	}
}

// endFloorLocked finishes a transmission, saves the clip, and tells everyone.
func (h *Hub) endFloorLocked(channel, reason string) {
	f := h.floors[channel]
	if f == nil {
		return
	}
	delete(h.floors, channel)
	_ = f.writer.Close()
	idStr := strconv.FormatUint(uint64(f.txID), 10)
	dur := f.writer.DurationMs()
	clipID := ""
	if f.writer.packets > 0 {
		clipID = idStr
		cl := &Clip{ID: idStr, ChannelID: f.channel, UserID: f.userID, Mode: f.mode, SOSID: f.sosID, StartedAt: f.started, DurationMs: dur, Bytes: f.writer.bytes}
		_ = h.s.store.Update(func(st *state) error {
			st.Clips[cl.ID] = cl
			if f.sosID != "" {
				if sos := st.SOS[f.sosID]; sos != nil {
					sos.ClipIDs = addUnique(sos.ClipIDs, cl.ID)
				}
			}
			return nil
		})
	} else {
		h.s.clips.Delete(idStr)
	}
	msg := encode(map[string]any{"t": "floor_ended", "channel": f.channel, "txId": f.txID, "userId": f.userID, "clipId": clipID, "durationMs": dur, "reason": reason})
	members, _ := h.channelMembers(f.channel)
	if !contains(members, f.userID) {
		members = append(members, f.userID)
	}
	for _, uid := range members {
		for _, c := range h.userConnsLocked(uid) {
			h.sendLocked(c, msg)
		}
	}
	if f.sosID != "" && clipID != "" {
		go h.s.broadcastSOS(f.sosID)
	}
}

func (h *Hub) watchdog() {
	t := time.NewTicker(500 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-h.stop:
			return
		case <-t.C:
		}
		now := time.Now()
		h.mu.Lock()
		for ch, f := range h.floors {
			switch {
			case now.After(f.deadline):
				h.endFloorLocked(ch, "timeout")
			case now.Sub(f.lastAudio) > idleTimeout:
				h.endFloorLocked(ch, "idle")
			}
		}
		h.mu.Unlock()
	}
}

// ---- used by HTTP handlers (never while holding the store lock) ----

func (h *Hub) SendTo(userIDs []string, v any) {
	msg := encode(v)
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, uid := range userIDs {
		for _, c := range h.userConnsLocked(uid) {
			h.sendLocked(c, msg)
		}
	}
}

func (h *Hub) Broadcast(v any) {
	msg := encode(v)
	h.mu.Lock()
	defer h.mu.Unlock()
	for uid := range h.conns {
		for _, c := range h.userConnsLocked(uid) {
			h.sendLocked(c, msg)
		}
	}
}

func (h *Hub) Connected(userID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.userConnsLocked(userID)) > 0
}

func (h *Hub) Kick(userID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, c := range h.userConnsLocked(userID) {
		c.kill()
	}
}

func (h *Hub) EndChannel(channel string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.endFloorLocked(channel, "released")
}

func (h *Hub) ActiveTxIDs() map[string]bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := map[string]bool{}
	for _, f := range h.floors {
		out[strconv.FormatUint(uint64(f.txID), 10)] = true
	}
	return out
}

func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return
	}
	h.closed = true
	for ch := range h.floors {
		h.endFloorLocked(ch, "disconnected")
	}
	for uid := range h.conns {
		for _, c := range h.userConnsLocked(uid) {
			c.kill()
		}
	}
	close(h.stop)
}
