package app

import (
	"errors"
	"fmt"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"
)

// ---- public JSON shapes (never expose token hashes or device tokens) ----

type UserView struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Role     Role   `json:"role"`
	Status   Status `json:"status"`
	Platform string `json:"platform"`
	Phone    string `json:"phone,omitempty"`
	Online   bool   `json:"online"`
	Duty     bool   `json:"duty"`
}

type ChannelView struct {
	ID        string     `json:"id"`
	Name      string     `json:"name"`
	Color     string     `json:"color"`
	IsDefault bool       `json:"isDefault"`
	Members   []UserView `json:"members"`
}

type InviteView struct {
	ID        string    `json:"id"`
	ChannelID string    `json:"channelId"`
	Label     string    `json:"label"`
	CreatedAt time.Time `json:"createdAt"`
	ExpiresAt time.Time `json:"expiresAt"`
}

func (s *Server) userView(u *User) UserView {
	online, duty := s.hub.Presence(u.ID)
	return UserView{ID: u.ID, Name: u.Name, Role: u.Role, Status: u.Status, Platform: u.Platform, Phone: u.Phone, Online: online, Duty: duty}
}

func (s *Server) channelView(st *state, c *Channel) ChannelView {
	cv := ChannelView{ID: c.ID, Name: c.Name, Color: c.Color, IsDefault: c.IsDefault, Members: []UserView{}}
	for _, id := range c.Members {
		if u := st.Users[id]; u != nil {
			cv.Members = append(cv.Members, s.userView(u))
		}
	}
	return cv
}

func cleanName(n string, max int) (string, error) {
	n = strings.TrimSpace(n)
	if n == "" {
		return "", errors.New("name is required")
	}
	if !utf8.ValidString(n) || utf8.RuneCountInString(n) > max {
		return "", fmt.Errorf("name must be at most %d characters", max)
	}
	for _, r := range n {
		if r < 0x20 {
			return "", errors.New("name has invalid characters")
		}
	}
	return n, nil
}

// cleanPhone keeps a leading + and digits; empty clears the number.
func cleanPhone(p string) (string, error) {
	var b strings.Builder
	for i, r := range strings.TrimSpace(p) {
		switch {
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case r == '+' && i == 0:
			b.WriteRune(r)
		case r == ' ' || r == '-' || r == '(' || r == ')':
		default:
			return "", errors.New("phone number has invalid characters")
		}
	}
	out := b.String()
	if out != "" && (len(out) < 6 || len(out) > 16) {
		return "", errors.New("phone number length looks wrong")
	}
	return out, nil
}

var allowedColors = map[string]bool{"green": true, "blue": true, "orange": true, "pink": true, "purple": true, "red": true, "teal": true, "yellow": true}

// ---- join ----

func (s *Server) handleJoin(w http.ResponseWriter, r *http.Request) {
	if !s.limit.allow("join:" + clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "Too many attempts. Try again in a minute.")
		return
	}
	var req struct {
		Code     string `json:"code"`
		Name     string `json:"name"`
		Platform string `json:"platform"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	name, err := cleanName(req.Name, 40)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
		return
	}
	if req.Platform != "ios" && req.Platform != "android" {
		writeErr(w, http.StatusBadRequest, "bad_platform", "platform must be ios or android")
		return
	}
	codeHash := hashSecret(normalizeCode(req.Code))
	token := newToken()
	var out *User
	var admins []string
	err = s.store.Update(func(st *state) error {
		var inv *Invite
		for _, i := range st.Invites {
			if i.CodeHash == codeHash {
				inv = i
				break
			}
		}
		if inv == nil || inv.UsedBy != "" || time.Now().After(inv.ExpiresAt) {
			return errInvite
		}
		u := &User{
			ID: newID(), Name: name, Role: RoleMember, Status: StatusPending,
			Platform: req.Platform, CreatedAt: time.Now().UTC(), TokenHash: hashSecret(token),
			InviteChan: inv.ChannelID, InviteLabel: inv.Label,
		}
		if inv.Admin {
			u.Role = RoleAdmin
			u.Status = StatusActive
			if def := st.defaultChannel(); def != nil {
				def.Members = addUnique(def.Members, u.ID)
			}
		}
		inv.UsedBy = u.ID
		st.Users[u.ID] = u
		c := *u
		out = &c
		for _, x := range st.Users {
			if x.Role == RoleAdmin && x.ID != u.ID {
				admins = append(admins, x.ID)
			}
		}
		return nil
	})
	if errors.Is(err, errInvite) {
		writeErr(w, http.StatusNotFound, "invalid_invite", "This invite is wrong, already used, or expired. Ask for a new one.")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "Could not save")
		return
	}
	if out.Status == StatusPending {
		s.hub.SendTo(admins, map[string]any{"t": "users_changed"})
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "user": s.userView(out)})
}

var errInvite = errors.New("invalid invite")

var joinPage = template.Must(template.New("join").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Join Walkie</title>
<style>
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,Roboto,system-ui,sans-serif;background:#000;color:#fff;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:420px;padding:32px 24px}
h1{font-size:30px;margin:0 0 8px}
p{color:rgba(235,235,245,.7);line-height:1.45}
a.btn{display:block;text-align:center;background:#30D158;color:#00210B;text-decoration:none;font-weight:700;font-size:18px;padding:16px;border-radius:28px;margin:24px 0}
code{font-size:22px;letter-spacing:2px;background:#1C1C1E;padding:8px 12px;border-radius:10px;display:inline-block}
</style></head><body><main>
<h1>Join Walkie</h1>
<p>You have been invited to a private family walkie-talkie.</p>
<a class="btn" href="{{.AppURL}}">Open in Walkie</a>
<p>Don't have the app yet? Ask the person who invited you for the iPhone (TestFlight) or Android install link, install it, then come back and tap the button.</p>
<p>Or open the app and type this code:</p>
<p><code>{{.Code}}</code></p>
<p>Server: {{.Server}}</p>
</main></body></html>`))

func (s *Server) appURL(code string) string {
	return "walkie://join?s=" + url.QueryEscape(s.cfg.PublicURL) + "&c=" + url.QueryEscape(code)
}

func (s *Server) handleJoinPage(w http.ResponseWriter, r *http.Request) {
	code := normalizeCode(r.URL.Query().Get("c"))
	if len(code) != 10 {
		http.Error(w, "Invalid invite link", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// template.URL marks our own walkie:// link as safe (html/template would
	// otherwise replace a non-http scheme with "#ZgotmplZ").
	_ = joinPage.Execute(w, map[string]any{"AppURL": template.URL(s.appURL(code)), "Code": code, "Server": s.cfg.PublicURL})
}

// ---- me ----

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request, u User) {
	var set Settings
	s.store.View(func(st *state) { set = st.Settings })
	writeJSON(w, http.StatusOK, map[string]any{"user": s.userView(&u), "settings": set, "server": map[string]any{"version": Version, "pushIos": s.cfg.APNs != nil, "pushAndroid": s.cfg.FCM != nil}})
}

func (s *Server) handleUpdateMe(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Name  *string `json:"name"`
		Phone *string `json:"phone"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	var name, phone string
	var err error
	if req.Name != nil {
		if name, err = cleanName(*req.Name, 40); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
			return
		}
	}
	if req.Phone != nil {
		if phone, err = cleanPhone(*req.Phone); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_phone", err.Error())
			return
		}
	}
	var out User
	err = s.store.Update(func(st *state) error {
		x := st.Users[u.ID]
		if x == nil {
			return ErrNotFound
		}
		if req.Name != nil {
			x.Name = name
		}
		if req.Phone != nil {
			x.Phone = phone
		}
		out = *x
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "User not found")
		return
	}
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]any{"user": s.userView(&out)})
}

func (s *Server) handlePushTokens(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		APNsPTT     *string `json:"apnsPtt"`
		APNsAlert   *string `json:"apnsAlert"`
		APNsSandbox *bool   `json:"apnsSandbox"`
		FCM         *string `json:"fcm"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	valid := func(p *string) bool { return p == nil || len(*p) <= 4096 }
	if !valid(req.APNsPTT) || !valid(req.APNsAlert) || !valid(req.FCM) {
		writeErr(w, http.StatusBadRequest, "bad_request", "Token too long")
		return
	}
	err := s.store.Update(func(st *state) error {
		x := st.Users[u.ID]
		if x == nil {
			return ErrNotFound
		}
		if req.APNsPTT != nil {
			x.Devices.APNsPTT = *req.APNsPTT
		}
		if req.APNsAlert != nil {
			x.Devices.APNsAlert = *req.APNsAlert
		}
		if req.APNsSandbox != nil {
			x.Devices.APNsSandbox = *req.APNsSandbox
		}
		if req.FCM != nil {
			x.Devices.FCM = *req.FCM
		}
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "User not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- users ----

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request, u User) {
	out := []UserView{}
	s.store.View(func(st *state) {
		for _, x := range st.Users {
			if x.Status == StatusPending && u.Role != RoleAdmin {
				continue
			}
			out = append(out, s.userView(x))
		}
	})
	sortUsers(out)
	writeJSON(w, http.StatusOK, map[string]any{"users": out})
}

func (s *Server) handleApprove(w http.ResponseWriter, r *http.Request, u User) {
	id := r.PathValue("id")
	var out User
	err := s.store.Update(func(st *state) error {
		x := st.Users[id]
		if x == nil {
			return ErrNotFound
		}
		x.Status = StatusActive
		ch := st.Channels[x.InviteChan]
		if ch == nil {
			ch = st.defaultChannel()
		}
		if ch != nil {
			ch.Members = addUnique(ch.Members, x.ID)
		}
		out = *x
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "User not found")
		return
	}
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]any{"user": s.userView(&out)})
}

func (s *Server) handleDeleteUser(w http.ResponseWriter, r *http.Request, u User) {
	id := r.PathValue("id")
	if id == u.ID {
		writeErr(w, http.StatusBadRequest, "self", "You can't remove yourself")
		return
	}
	err := s.store.Update(func(st *state) error {
		if st.Users[id] == nil {
			return ErrNotFound
		}
		st.removeUserEverywhere(id)
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "User not found")
		return
	}
	s.hub.Kick(id)
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- channels ----

func (s *Server) handleChannels(w http.ResponseWriter, r *http.Request, u User) {
	out := []ChannelView{}
	s.store.View(func(st *state) {
		var cs []*Channel
		if u.Role == RoleAdmin {
			for _, c := range st.Channels {
				cs = append(cs, c)
			}
			sortChannels(cs)
		} else {
			cs = st.channelsOf(u.ID)
		}
		for _, c := range cs {
			out = append(out, s.channelView(st, c))
		}
	})
	writeJSON(w, http.StatusOK, map[string]any{"channels": out})
}

func (s *Server) handleCreateChannel(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Name      string   `json:"name"`
		Color     string   `json:"color"`
		MemberIDs []string `json:"memberIds"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	name, err := cleanName(req.Name, 30)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", err.Error())
		return
	}
	if !allowedColors[req.Color] {
		req.Color = "blue"
	}
	var cv ChannelView
	err = s.store.Update(func(st *state) error {
		if len(st.Channels) >= 20 {
			return errors.New("too many channels")
		}
		c := &Channel{ID: newID(), Name: name, Color: req.Color, Members: []string{u.ID}, CreatedAt: time.Now().UTC()}
		for _, id := range req.MemberIDs {
			if x := st.Users[id]; x != nil && x.Status == StatusActive {
				c.Members = addUnique(c.Members, id)
			}
		}
		st.Channels[c.ID] = c
		cv = s.channelView(st, c)
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, "limit", "You already have 20 channels")
		return
	}
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]any{"channel": cv})
}

func (s *Server) handleUpdateChannel(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		Name  *string `json:"name"`
		Color *string `json:"color"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	var cv ChannelView
	var vErr error
	err := s.store.Update(func(st *state) error {
		c := st.Channels[r.PathValue("id")]
		if c == nil {
			return ErrNotFound
		}
		if req.Name != nil {
			n, err := cleanName(*req.Name, 30)
			if err != nil {
				vErr = err
				return err
			}
			c.Name = n
		}
		if req.Color != nil && allowedColors[*req.Color] {
			c.Color = *req.Color
		}
		cv = s.channelView(st, c)
		return nil
	})
	if vErr != nil {
		writeErr(w, http.StatusBadRequest, "bad_name", vErr.Error())
		return
	}
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "Channel not found")
		return
	}
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]any{"channel": cv})
}

func (s *Server) handleSetMembers(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		MemberIDs []string `json:"memberIds"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	var cv ChannelView
	err := s.store.Update(func(st *state) error {
		c := st.Channels[r.PathValue("id")]
		if c == nil {
			return ErrNotFound
		}
		members := []string{}
		for _, id := range req.MemberIDs {
			if x := st.Users[id]; x != nil && x.Status == StatusActive {
				members = addUnique(members, id)
			}
		}
		// The admin always stays in every channel they manage.
		members = addUnique(members, u.ID)
		c.Members = members
		cv = s.channelView(st, c)
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "Channel not found")
		return
	}
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]any{"channel": cv})
}

func (s *Server) handleDeleteChannel(w http.ResponseWriter, r *http.Request, u User) {
	id := r.PathValue("id")
	var isDefault bool
	err := s.store.Update(func(st *state) error {
		c := st.Channels[id]
		if c == nil {
			return ErrNotFound
		}
		if c.IsDefault {
			isDefault = true
			return errors.New("default")
		}
		delete(st.Channels, id)
		for cid, cl := range st.Clips {
			if cl.ChannelID == id && cl.SOSID == "" {
				delete(st.Clips, cid)
				s.clips.Delete(cid)
			}
		}
		return nil
	})
	if isDefault {
		writeErr(w, http.StatusBadRequest, "default_channel", "The Family channel can't be deleted")
		return
	}
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "Channel not found")
		return
	}
	s.hub.EndChannel(id)
	s.broadcastChannelsChanged()
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) broadcastChannelsChanged() {
	s.hub.Broadcast(map[string]any{"t": "channels_changed"})
}

// ---- invites ----

func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request, u User) {
	var req struct {
		ChannelID string `json:"channelId"`
		Label     string `json:"label"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	label := strings.TrimSpace(req.Label)
	if utf8.RuneCountInString(label) > 60 {
		writeErr(w, http.StatusBadRequest, "bad_label", "Label is too long")
		return
	}
	code := newInviteCode()
	var iv InviteView
	err := s.store.Update(func(st *state) error {
		ch := st.Channels[req.ChannelID]
		if ch == nil {
			ch = st.defaultChannel()
		}
		active := 0
		for _, i := range st.Invites {
			if i.UsedBy == "" && time.Now().Before(i.ExpiresAt) {
				active++
			}
		}
		if active >= 20 {
			return errors.New("too many invites")
		}
		inv := &Invite{ID: newID(), CodeHash: hashSecret(code), ChannelID: ch.ID, Label: label, CreatedAt: time.Now().UTC(), ExpiresAt: time.Now().UTC().Add(24 * time.Hour)}
		st.Invites[inv.ID] = inv
		iv = InviteView{ID: inv.ID, ChannelID: inv.ChannelID, Label: inv.Label, CreatedAt: inv.CreatedAt, ExpiresAt: inv.ExpiresAt}
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, "limit", "Too many open invites. Delete some first.")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"invite": iv, "code": code, "url": s.cfg.PublicURL + "/join?c=" + code, "appUrl": s.appURL(code)})
}

func (s *Server) handleListInvites(w http.ResponseWriter, r *http.Request, u User) {
	out := []InviteView{}
	s.store.View(func(st *state) {
		for _, i := range st.Invites {
			if i.UsedBy == "" && time.Now().Before(i.ExpiresAt) && !i.Admin {
				out = append(out, InviteView{ID: i.ID, ChannelID: i.ChannelID, Label: i.Label, CreatedAt: i.CreatedAt, ExpiresAt: i.ExpiresAt})
			}
		}
	})
	writeJSON(w, http.StatusOK, map[string]any{"invites": out})
}

func (s *Server) handleDeleteInvite(w http.ResponseWriter, r *http.Request, u User) {
	err := s.store.Update(func(st *state) error {
		if st.Invites[r.PathValue("id")] == nil {
			return ErrNotFound
		}
		delete(st.Invites, r.PathValue("id"))
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "Invite not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// CreateAdminInvite is used by `walkie setup` on the server itself.
func (s *Server) CreateAdminInvite() (code, link, appLink string, err error) {
	code = newInviteCode()
	err = s.store.Update(func(st *state) error {
		def := st.defaultChannel()
		inv := &Invite{ID: newID(), CodeHash: hashSecret(code), ChannelID: def.ID, Label: "Admin", Admin: true, CreatedAt: time.Now().UTC(), ExpiresAt: time.Now().UTC().Add(24 * time.Hour)}
		st.Invites[inv.ID] = inv
		return nil
	})
	return code, s.cfg.PublicURL + "/join?c=" + code, s.appURL(code), err
}

// ---- settings ----

func (s *Server) handleGetSettings(w http.ResponseWriter, r *http.Request, u User) {
	var set Settings
	s.store.View(func(st *state) { set = st.Settings })
	writeJSON(w, http.StatusOK, map[string]any{"settings": set})
}

func (s *Server) handlePutSettings(w http.ResponseWriter, r *http.Request, u User) {
	var req Settings
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "Invalid request")
		return
	}
	if req.ClipTTLHours < 1 || req.ClipTTLHours > 168 {
		writeErr(w, http.StatusBadRequest, "bad_ttl", "Clip lifetime must be 1 to 168 hours")
		return
	}
	var set Settings
	_ = s.store.Update(func(st *state) error {
		st.Settings.ClipTTLHours = req.ClipTTLHours
		set = st.Settings
		return nil
	})
	s.hub.Broadcast(map[string]any{"t": "settings_changed", "settings": set})
	go s.sweepOnce()
	writeJSON(w, http.StatusOK, map[string]any{"settings": set})
}
