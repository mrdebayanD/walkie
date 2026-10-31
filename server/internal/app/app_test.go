package app

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type fakePush struct {
	mu   sync.Mutex
	sent []string
}

func (f *fakePush) rec(s string) { f.mu.Lock(); f.sent = append(f.sent, s); f.mu.Unlock() }
func (f *fakePush) PushAPNsPTT(t string, sb bool, m PushMessage) error {
	f.rec("ptt:" + t + ":" + m.Speaker)
	return nil
}
func (f *fakePush) PushAPNsAlert(t string, sb bool, m PushMessage) error {
	f.rec("alert:" + t + ":" + kindName(m.Kind))
	return nil
}
func (f *fakePush) PushFCM(t string, m PushMessage) error {
	f.rec("fcm:" + t + ":" + kindName(m.Kind))
	return nil
}
func (f *fakePush) has(prefix string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, s := range f.sent {
		if strings.HasPrefix(s, prefix) {
			return true
		}
	}
	return false
}

type env struct {
	t    *testing.T
	srv  *Server
	ts   *httptest.Server
	push *fakePush
}

func newEnv(t *testing.T) *env {
	t.Helper()
	fp := &fakePush{}
	s, err := New(Config{DataDir: t.TempDir(), PublicURL: "https://walkie.test"}, fp)
	if err != nil {
		t.Fatal(err)
	}
	s.logf = t.Logf
	ts := httptest.NewServer(s.Handler())
	ctx, cancel := context.WithCancel(context.Background())
	go s.sosRep.loop(ctx)
	t.Cleanup(func() { cancel(); s.hub.Close(); ts.Close() })
	return &env{t: t, srv: s, ts: ts, push: fp}
}

func (e *env) do(method, path, token string, body any, out any) int {
	e.t.Helper()
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.ts.URL+path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		b, _ := io.ReadAll(resp.Body)
		if len(b) > 0 && b[0] == '{' {
			if err := json.Unmarshal(b, out); err != nil {
				e.t.Fatalf("decode %s: %v (%s)", path, err, b)
			}
		} else if bp, ok := out.(*[]byte); ok {
			*bp = b
		}
	}
	return resp.StatusCode
}

type joinResp struct {
	Token string   `json:"token"`
	User  UserView `json:"user"`
}

// admin creates the admin, a member invite, and returns both tokens.
func (e *env) family(platform string) (admin, member joinResp, familyID string) {
	code, _, _, err := e.srv.CreateAdminInvite()
	if err != nil {
		e.t.Fatal(err)
	}
	if st := e.do("POST", "/v1/join", "", map[string]string{"code": code, "name": "Debayan", "platform": "ios"}, &admin); st != 200 {
		e.t.Fatalf("admin join %d", st)
	}
	if admin.User.Role != RoleAdmin || admin.User.Status != StatusActive {
		e.t.Fatalf("admin not active: %+v", admin.User)
	}
	var chans struct{ Channels []ChannelView }
	e.do("GET", "/v1/channels", admin.Token, nil, &chans)
	familyID = chans.Channels[0].ID
	var inv struct {
		Code string `json:"code"`
		URL  string `json:"url"`
	}
	if st := e.do("POST", "/v1/invites", admin.Token, map[string]string{"channelId": familyID, "label": "Ma"}, &inv); st != 200 {
		e.t.Fatalf("invite %d", st)
	}
	if !strings.HasPrefix(inv.URL, "https://walkie.test/join?c=") {
		e.t.Fatalf("bad invite url %s", inv.URL)
	}
	if st := e.do("POST", "/v1/join", "", map[string]string{"code": inv.Code, "name": "Ma", "platform": platform}, &member); st != 200 {
		e.t.Fatalf("member join %d", st)
	}
	if member.User.Status != StatusPending {
		e.t.Fatalf("member should be pending")
	}
	// Pending users can't see channels.
	if st := e.do("GET", "/v1/channels", member.Token, nil, nil); st != 403 {
		e.t.Fatalf("pending user got %d", st)
	}
	// Invite is single use.
	if st := e.do("POST", "/v1/join", "", map[string]string{"code": inv.Code, "name": "X", "platform": "ios"}, nil); st != 404 {
		e.t.Fatalf("reused invite got %d", st)
	}
	if st := e.do("POST", "/v1/users/"+member.User.ID+"/approve", admin.Token, nil, nil); st != 200 {
		e.t.Fatalf("approve %d", st)
	}
	return admin, member, familyID
}

type wsClient struct {
	t  *testing.T
	ws *websocket.Conn
	ch chan any // map[string]any for text, []byte for binary
}

func (e *env) connect(token string, duty bool) *wsClient {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(e.ts.URL, "http")+"/v1/ws", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		e.t.Fatal(err)
	}
	c := &wsClient{t: e.t, ws: ws, ch: make(chan any, 100)}
	c.sendJSON(map[string]any{"t": "hello", "duty": duty, "app": "test"})
	go func() {
		for {
			typ, data, err := ws.Read(context.Background())
			if err != nil {
				close(c.ch)
				return
			}
			if typ == websocket.MessageBinary {
				c.ch <- data
			} else {
				var m map[string]any
				_ = json.Unmarshal(data, &m)
				c.ch <- m
			}
		}
	}()
	c.expect("welcome")
	e.t.Cleanup(func() { ws.CloseNow() })
	return c
}

func (c *wsClient) sendJSON(v any) {
	b, _ := json.Marshal(v)
	if err := c.ws.Write(context.Background(), websocket.MessageText, b); err != nil {
		c.t.Fatal(err)
	}
}

func (c *wsClient) sendAudio(packets ...[]byte) {
	var b []byte
	b = append(b, 0x01)
	for _, p := range packets {
		b = binary.BigEndian.AppendUint16(b, uint16(len(p)))
		b = append(b, p...)
	}
	if err := c.ws.Write(context.Background(), websocket.MessageBinary, b); err != nil {
		c.t.Fatal(err)
	}
}

// expect waits for a text message of type t, skipping presence noise.
func (c *wsClient) expect(t string) map[string]any {
	c.t.Helper()
	timeout := time.After(5 * time.Second)
	for {
		select {
		case m, ok := <-c.ch:
			if !ok {
				c.t.Fatalf("connection closed waiting for %s", t)
			}
			if mm, ok := m.(map[string]any); ok {
				if mm["t"] == t {
					return mm
				}
				if mm["t"] == "presence" || mm["t"] == "channels_changed" || mm["t"] == "users_changed" {
					continue
				}
				c.t.Fatalf("waiting for %s, got %v", t, mm)
			}
		case <-timeout:
			c.t.Fatalf("timed out waiting for %s", t)
		}
	}
}

func (c *wsClient) expectAudio() []byte {
	c.t.Helper()
	timeout := time.After(5 * time.Second)
	for {
		select {
		case m := <-c.ch:
			if b, ok := m.([]byte); ok {
				return b
			}
		case <-timeout:
			c.t.Fatalf("timed out waiting for audio")
		}
	}
}

func TestTalkRelayHistory(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("android")

	a := e.connect(admin.Token, true)
	m := e.connect(ma.Token, true)

	a.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "hold"})
	g := a.expect("floor_granted")
	tx := uint32(g["txId"].(float64))
	taken := m.expect("floor_taken")
	if taken["name"] != "Debayan" {
		t.Fatalf("floor_taken name %v", taken["name"])
	}

	// Ma can't talk over.
	m.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "hold"})
	if d := m.expect("floor_denied"); d["reason"] != "busy" {
		t.Fatalf("expected busy, got %v", d)
	}

	a.sendAudio([]byte("opus-1"), []byte("opus-2"), []byte("opus-3"))
	got := m.expectAudio()
	if got[0] != 1 || binary.BigEndian.Uint32(got[1:5]) != tx {
		t.Fatalf("bad audio header %v", got[:5])
	}
	if !bytes.Contains(got, []byte("opus-3")) {
		t.Fatalf("audio payload missing")
	}

	a.sendJSON(map[string]any{"t": "floor_release"})
	end := m.expect("floor_ended")
	if end["reason"] != "released" || end["clipId"] == "" || end["durationMs"].(float64) != 60 {
		t.Fatalf("bad floor_ended %v", end)
	}

	var clips struct{ Clips []ClipView }
	e.do("GET", "/v1/channels/"+family+"/clips", ma.Token, nil, &clips)
	if len(clips.Clips) != 1 || clips.Clips[0].UserName != "Debayan" || clips.Clips[0].ExpiresAt == nil {
		t.Fatalf("bad clips %+v", clips.Clips)
	}
	var audio []byte
	e.do("GET", "/v1/clips/"+clips.Clips[0].ID+"/audio", ma.Token, nil, &audio)
	if string(audio[:4]) != "WKC1" || binary.BigEndian.Uint32(audio[4:8]) != 48000 || !bytes.Contains(audio, []byte("opus-2")) {
		t.Fatalf("bad clip file %q", audio)
	}

	// TTL sweep removes old clips.
	_ = e.srv.store.Update(func(st *state) error {
		for _, c := range st.Clips {
			c.StartedAt = time.Now().Add(-25 * time.Hour)
		}
		return nil
	})
	e.srv.sweepOnce()
	e.do("GET", "/v1/channels/"+family+"/clips", ma.Token, nil, &clips)
	if len(clips.Clips) != 0 {
		t.Fatalf("clip not swept")
	}
}

func TestOfflineMemberGetsPushAndLateJoinCatchesUp(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("ios")
	e.do("PUT", "/v1/me/push", ma.Token, map[string]any{"apnsPtt": "PTTTOKEN", "apnsAlert": "ALERTTOKEN"}, nil)

	a := e.connect(admin.Token, true)
	a.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "handsfree"})
	a.expect("floor_granted")
	a.sendAudio([]byte("early"))
	time.Sleep(200 * time.Millisecond)
	if !e.push.has("ptt:PTTTOKEN:Debayan") {
		t.Fatalf("no PTT push sent: %v", e.push.sent)
	}
	// Ma's phone wakes up and connects mid-transmission: gets the start again.
	m := e.connect(ma.Token, true)
	m.expect("floor_taken")
	if b := m.expectAudio(); !bytes.Contains(b, []byte("early")) {
		t.Fatalf("no catch-up audio")
	}
}

func TestIdleAndDisconnectEndFloor(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("android")
	m := e.connect(ma.Token, true)
	a := e.connect(admin.Token, true)
	a.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "hold"})
	a.expect("floor_granted")
	m.expect("floor_taken")
	a.ws.CloseNow()
	if end := m.expect("floor_ended"); end["reason"] != "disconnected" {
		t.Fatalf("expected disconnected, got %v", end)
	}
}

func TestSOSFlow(t *testing.T) {
	e := newEnv(t)
	admin, ma, _ := e.family("android")
	// Admin is the recipient here; give the admin an Android token for the check.
	_ = e.srv.store.Update(func(st *state) error {
		st.Users[admin.User.ID].Platform = "android"
		st.Users[admin.User.ID].Devices.FCM = "FCMTOKEN"
		return nil
	})
	a := e.connect(admin.Token, true)

	var resp struct{ SOS SOSView }
	if st := e.do("POST", "/v1/sos", ma.Token, map[string]any{"lat": 23.83, "lng": 91.28, "accuracy": 12}, &resp); st != 200 {
		t.Fatalf("sos %d", st)
	}
	if resp.SOS.State != "active" || len(resp.SOS.Recipients) != 1 || resp.SOS.Location == nil {
		t.Fatalf("bad sos %+v", resp.SOS)
	}
	ev := a.expect("sos")
	if ev["sos"].(map[string]any)["userName"] != "Ma" {
		t.Fatalf("bad sos event %v", ev)
	}
	time.Sleep(2500 * time.Millisecond)
	if !e.push.has("fcm:FCMTOKEN:sos") {
		t.Fatalf("no SOS push to Android recipient: %v", e.push.sent)
	}
	// A second SOS while one is active returns the same one.
	var again struct{ SOS SOSView }
	e.do("POST", "/v1/sos", ma.Token, map[string]any{}, &again)
	if again.SOS.ID != resp.SOS.ID {
		t.Fatalf("duplicate SOS created")
	}
	// Recipient responds; only sender/admin may resolve.
	if st := e.do("POST", "/v1/sos/"+resp.SOS.ID+"/respond", admin.Token, nil, nil); st != 200 {
		t.Fatalf("respond %d", st)
	}
	var out struct{ SOS SOSView }
	if st := e.do("POST", "/v1/sos/"+resp.SOS.ID+"/resolve", ma.Token, map[string]string{"resolution": "safe"}, &out); st != 200 || out.SOS.State != "resolved" {
		t.Fatalf("resolve %d %+v", st, out.SOS)
	}
	if !contains(out.SOS.Responders, admin.User.ID) {
		t.Fatalf("responder not recorded")
	}
}

func TestSOSPreemptsTalker(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("android")
	a := e.connect(admin.Token, true)
	m := e.connect(ma.Token, true)
	a.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "hold"})
	a.expect("floor_granted")
	m.expect("floor_taken")
	var resp struct{ SOS SOSView }
	e.do("POST", "/v1/sos", ma.Token, map[string]any{}, &resp)
	m.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "sos", "sosId": resp.SOS.ID})
	m.expect("sos")
	if end := m.expect("floor_ended"); end["reason"] != "preempted" {
		t.Fatalf("expected preempted, got %v", end)
	}
	m.expect("floor_granted")
	m.sendAudio([]byte("help"))
	m.sendJSON(map[string]any{"t": "floor_release"})
	m.expect("floor_ended")
	time.Sleep(100 * time.Millisecond)
	// SOS clips are exempt from the normal TTL.
	_ = e.srv.store.Update(func(st *state) error {
		for _, c := range st.Clips {
			c.StartedAt = time.Now().Add(-100 * time.Hour)
		}
		return nil
	})
	e.srv.sweepOnce()
	var list struct{ SOS []SOSView }
	e.do("GET", "/v1/sos", admin.Token, nil, &list)
	if len(list.SOS) != 1 || len(list.SOS[0].ClipIDs) != 1 {
		t.Fatalf("sos clip missing %+v", list.SOS)
	}
	var audio []byte
	if st := e.do("GET", "/v1/clips/"+list.SOS[0].ClipIDs[0]+"/audio", admin.Token, nil, &audio); st != 200 || !bytes.Contains(audio, []byte("help")) {
		t.Fatalf("sos clip swept or unreadable (%d)", st)
	}
}

func TestChannelsAndPermissions(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("ios")
	// Members can't create channels.
	if st := e.do("POST", "/v1/channels", ma.Token, map[string]any{"name": "X", "color": "blue"}, nil); st != 403 {
		t.Fatalf("member created channel: %d", st)
	}
	var created struct{ Channel ChannelView }
	if st := e.do("POST", "/v1/channels", admin.Token, map[string]any{"name": "Neighbours", "color": "blue", "memberIds": []string{ma.User.ID}}, &created); st != 200 {
		t.Fatalf("create channel %d", st)
	}
	if len(created.Channel.Members) != 2 {
		t.Fatalf("members %+v", created.Channel.Members)
	}
	if st := e.do("DELETE", "/v1/channels/"+family, admin.Token, nil, nil); st != 400 {
		t.Fatalf("default channel deletable: %d", st)
	}
	if st := e.do("PUT", "/v1/settings", admin.Token, map[string]int{"clipTtlHours": 500}, nil); st != 400 {
		t.Fatalf("bad ttl accepted")
	}
	if st := e.do("PUT", "/v1/settings", admin.Token, map[string]int{"clipTtlHours": 12}, nil); st != 200 {
		t.Fatalf("ttl not saved")
	}
	// Removing a user revokes their token.
	if st := e.do("DELETE", "/v1/users/"+ma.User.ID, admin.Token, nil, nil); st != 200 {
		t.Fatalf("delete user %d", st)
	}
	if st := e.do("GET", "/v1/me", ma.Token, nil, nil); st != 401 {
		t.Fatalf("removed user still authorized: %d", st)
	}
}

func TestBadAudioIgnored(t *testing.T) {
	if _, ok := countPackets([]byte{0, 5, 1, 2}); ok {
		t.Fatal("short packet accepted")
	}
	if _, ok := countPackets([]byte{0x10, 0x00}); ok {
		t.Fatal("oversized length accepted")
	}
	if n, ok := countPackets([]byte{0, 1, 9, 0, 2, 7, 7}); !ok || n != 2 {
		t.Fatal("valid payload rejected")
	}
}

func TestJoinRateLimit(t *testing.T) {
	e := newEnv(t)
	last := 0
	for i := 0; i < 12; i++ {
		last = e.do("POST", "/v1/join", "", map[string]string{"code": "WRONGCODE1", "name": "x", "platform": "ios"}, nil)
	}
	if last != 429 {
		t.Fatalf("expected rate limit, got %d", last)
	}
}

func TestJoinPageLinksToApp(t *testing.T) {
	e := newEnv(t)
	var page []byte
	if st := e.do("GET", "/join?c=ABCDE12345", "", nil, &page); st != 200 {
		t.Fatalf("join page %d", st)
	}
	if !bytes.Contains(page, []byte(`href="walkie://join?s=https%3a%2f%2fwalkie.test&amp;c=ABCDE12345"`)) && !bytes.Contains(page, []byte(`href="walkie://join?s=https%3A%2F%2Fwalkie.test&amp;c=ABCDE12345"`)) {
		t.Fatalf("app link missing:\n%s", page)
	}
}

func TestProfilePhone(t *testing.T) {
	e := newEnv(t)
	admin, ma, _ := e.family("ios")
	if st := e.do("PUT", "/v1/me", ma.Token, map[string]string{"phone": "+91 98765-43210"}, nil); st != 200 {
		t.Fatalf("phone %d", st)
	}
	if st := e.do("PUT", "/v1/me", ma.Token, map[string]string{"phone": "call me"}, nil); st != 400 {
		t.Fatalf("bad phone accepted")
	}
	var users struct{ Users []UserView }
	e.do("GET", "/v1/users", admin.Token, nil, &users)
	found := false
	for _, u := range users.Users {
		if u.ID == ma.User.ID && u.Phone == "+919876543210" && u.Name == "Ma" {
			found = true
		}
	}
	if !found {
		t.Fatalf("phone not visible %+v", users.Users)
	}
}

func TestAndroidAlwaysWokenIOSOnlyWhenOffline(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("android")
	e.do("PUT", "/v1/me/push", ma.Token, map[string]any{"fcm": "MAFCM"}, nil)
	e.connect(ma.Token, true) // connected, but Doze may have frozen it
	a := e.connect(admin.Token, true)
	a.sendJSON(map[string]any{"t": "floor_request", "channel": family, "mode": "hold"})
	a.expect("floor_granted")
	time.Sleep(200 * time.Millisecond)
	if !e.push.has("fcm:MAFCM:tx") {
		t.Fatalf("connected Android member not woken: %v", e.push.sent)
	}
}

// joinMember adds another approved member to the family and returns them.
func (e *env) joinMember(admin joinResp, family, name, platform string) joinResp {
	e.t.Helper()
	var inv struct {
		Code string `json:"code"`
	}
	e.do("POST", "/v1/invites", admin.Token, map[string]string{"channelId": family, "label": name}, &inv)
	var m joinResp
	if st := e.do("POST", "/v1/join", "", map[string]string{"code": inv.Code, "name": name, "platform": platform, "phone": "+919000000001"}, &m); st != 200 {
		e.t.Fatalf("join %s: %d", name, st)
	}
	if m.User.Phone != "+919000000001" {
		e.t.Fatalf("phone at join not saved: %+v", m.User)
	}
	e.do("POST", "/v1/users/"+m.User.ID+"/approve", admin.Token, nil, nil)
	return m
}

func TestPersonalChannelIsPrivate(t *testing.T) {
	e := newEnv(t)
	admin, ma, family := e.family("ios")
	didi := e.joinMember(admin, family, "Didi", "android")

	// Ma opens a personal channel with Didi; asking twice returns the same one.
	var c1, c2 struct{ Channel ChannelView }
	if st := e.do("POST", "/v1/direct", ma.Token, map[string]string{"userId": didi.User.ID}, &c1); st != 200 {
		t.Fatalf("direct %d", st)
	}
	e.do("POST", "/v1/direct", didi.Token, map[string]string{"userId": ma.User.ID}, &c2)
	if c1.Channel.ID != c2.Channel.ID || c1.Channel.Kind != "direct" || len(c1.Channel.Members) != 2 {
		t.Fatalf("bad personal channel %+v / %+v", c1.Channel, c2.Channel)
	}
	pid := c1.Channel.ID

	// The admin doesn't see it, can't change it, can't read its history.
	var chans struct{ Channels []ChannelView }
	e.do("GET", "/v1/channels", admin.Token, nil, &chans)
	for _, c := range chans.Channels {
		if c.ID == pid {
			t.Fatal("admin can see a personal channel")
		}
	}
	if st := e.do("GET", "/v1/channels/"+pid+"/clips", admin.Token, nil, nil); st != 404 {
		t.Fatalf("admin read personal history: %d", st)
	}
	if st := e.do("PUT", "/v1/channels/"+pid+"/members", admin.Token, map[string]any{"memberIds": []string{admin.User.ID}}, nil); st != 404 {
		t.Fatalf("admin changed personal channel: %d", st)
	}
	if st := e.do("POST", "/v1/invites", admin.Token, map[string]string{"channelId": pid}, nil); st != 200 {
		t.Fatalf("invite fallback failed: %d", st)
	}

	// Ma talks; Didi hears; the admin (connected, on duty) gets nothing.
	a := e.connect(admin.Token, true)
	m := e.connect(ma.Token, true)
	d := e.connect(didi.Token, true)
	m.sendJSON(map[string]any{"t": "floor_request", "channel": pid, "mode": "hold"})
	m.expect("floor_granted")
	d.expect("floor_taken")
	m.sendAudio([]byte("secret"))
	if b := d.expectAudio(); !bytes.Contains(b, []byte("secret")) {
		t.Fatal("Didi didn't hear")
	}
	m.sendJSON(map[string]any{"t": "floor_release"})
	end := d.expect("floor_ended")
	deadline := time.After(400 * time.Millisecond)
drain:
	for {
		select {
		case msg := <-a.ch:
			if mm, ok := msg.(map[string]any); ok && (mm["t"] == "floor_taken" || mm["t"] == "floor_ended") {
				t.Fatalf("admin received personal traffic: %v", mm)
			}
			if _, ok := msg.([]byte); ok {
				t.Fatal("admin received personal audio")
			}
		case <-deadline:
			break drain
		}
	}
	if st := e.do("GET", "/v1/clips/"+end["clipId"].(string)+"/audio", admin.Token, nil, nil); st != 404 {
		t.Fatalf("admin replayed personal clip: %d", st)
	}
	var clips struct{ Clips []ClipView }
	e.do("GET", "/v1/channels/"+pid+"/clips", didi.Token, nil, &clips)
	if len(clips.Clips) != 1 {
		t.Fatalf("Didi can't see personal history")
	}

	// Either member can delete it; removing a member deletes theirs.
	if st := e.do("DELETE", "/v1/channels/"+pid, didi.Token, nil, nil); st != 200 {
		t.Fatalf("member couldn't delete own personal channel: %d", st)
	}
	var again struct{ Channel ChannelView }
	e.do("POST", "/v1/direct", ma.Token, map[string]string{"userId": admin.User.ID}, &again)
	if st := e.do("DELETE", "/v1/users/"+ma.User.ID, admin.Token, nil, nil); st != 200 {
		t.Fatalf("remove %d", st)
	}
	e.do("GET", "/v1/channels", admin.Token, nil, &chans)
	for _, c := range chans.Channels {
		if c.Kind == "direct" {
			t.Fatal("personal channel survived its member's removal")
		}
	}
	// Members can't delete group channels.
	if st := e.do("DELETE", "/v1/channels/"+family, didi.Token, nil, nil); st != 403 {
		t.Fatalf("member deleted group channel: %d", st)
	}
}

func TestAdminSetsMemberPhone(t *testing.T) {
	e := newEnv(t)
	admin, ma, _ := e.family("ios")
	if st := e.do("PUT", "/v1/users/"+ma.User.ID, admin.Token, map[string]string{"phone": "+91 98765 00000"}, nil); st != 200 {
		t.Fatalf("admin set phone %d", st)
	}
	if st := e.do("PUT", "/v1/users/"+admin.User.ID, ma.Token, map[string]string{"phone": "1234567"}, nil); st != 403 {
		t.Fatalf("member edited someone else: %d", st)
	}
	var me struct{ User UserView }
	e.do("GET", "/v1/me", ma.Token, nil, &me)
	if me.User.Phone != "+919876500000" {
		t.Fatalf("phone not saved: %q", me.User.Phone)
	}
}

func TestInvitePageOffersInstallers(t *testing.T) {
	e := newEnv(t)
	apk := t.TempDir() + "/walkie.apk"
	if err := os.WriteFile(apk, []byte("PK-fake-apk"), 0o600); err != nil {
		t.Fatal(err)
	}
	e.srv.cfg.AndroidAPK = apk
	e.srv.cfg.TestFlightURL = "https://testflight.apple.com/join/ABCD1234"
	var page []byte
	e.do("GET", "/join?c=ABCDE12345", "", nil, &page)
	if !bytes.Contains(page, []byte("/install/walkie.apk")) || !bytes.Contains(page, []byte("testflight.apple.com/join/ABCD1234")) {
		t.Fatalf("install links missing:\n%s", page)
	}
	var body []byte
	if st := e.do("GET", "/install/walkie.apk", "", nil, &body); st != 200 || string(body) != "PK-fake-apk" {
		t.Fatalf("apk download %d %q", st, body)
	}
}
