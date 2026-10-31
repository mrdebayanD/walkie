package app

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// The whole state is small (a family: ~10 users, a few channels, a day of
// clip metadata), so it lives in memory and is written atomically to one
// JSON file on every change. Audio bytes live in separate clip files.

type Role string
type Status string

const (
	RoleAdmin  Role = "admin"
	RoleMember Role = "member"

	StatusPending Status = "pending"
	StatusActive  Status = "active"
)

type Devices struct {
	APNsPTT     string `json:"apnsPtt,omitempty"`
	APNsAlert   string `json:"apnsAlert,omitempty"`
	APNsSandbox bool   `json:"apnsSandbox,omitempty"`
	FCM         string `json:"fcm,omitempty"`
}

type User struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Role        Role      `json:"role"`
	Status      Status    `json:"status"`
	Platform    string    `json:"platform"`
	Phone       string    `json:"phone,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
	TokenHash   string    `json:"tokenHash"`
	Devices     Devices   `json:"devices"`
	InviteChan  string    `json:"inviteChannel,omitempty"`
	InviteLabel string    `json:"inviteLabel,omitempty"`
}

type Channel struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Color     string `json:"color"`
	IsDefault bool   `json:"isDefault"`
	// Kind is "group" (admin-managed) or "direct" (a private channel between
	// exactly two people; the admin can't see, change or replay it).
	Kind      string    `json:"kind,omitempty"`
	Members   []string  `json:"members"`
	CreatedAt time.Time `json:"createdAt"`
}

type Invite struct {
	ID        string    `json:"id"`
	CodeHash  string    `json:"codeHash"`
	ChannelID string    `json:"channelId"`
	Label     string    `json:"label"`
	Admin     bool      `json:"admin"`
	CreatedAt time.Time `json:"createdAt"`
	ExpiresAt time.Time `json:"expiresAt"`
	UsedBy    string    `json:"usedBy,omitempty"`
}

type Clip struct {
	ID         string    `json:"id"`
	ChannelID  string    `json:"channelId"`
	UserID     string    `json:"userId"`
	Mode       string    `json:"mode"`
	SOSID      string    `json:"sosId,omitempty"`
	StartedAt  time.Time `json:"startedAt"`
	DurationMs int64     `json:"durationMs"`
	Bytes      int64     `json:"bytes"`
}

type Location struct {
	Lat      float64   `json:"lat"`
	Lng      float64   `json:"lng"`
	Accuracy float64   `json:"accuracy"`
	At       time.Time `json:"at"`
}

type SOS struct {
	ID         string     `json:"id"`
	UserID     string     `json:"userId"`
	ChannelID  string     `json:"channelId"`
	CreatedAt  time.Time  `json:"createdAt"`
	State      string     `json:"state"` // active | resolved
	Resolution string     `json:"resolution,omitempty"`
	ResolvedAt *time.Time `json:"resolvedAt,omitempty"`
	ResolvedBy string     `json:"resolvedBy,omitempty"`
	Location   *Location  `json:"location,omitempty"`
	Recipients []string   `json:"recipients"`
	Acked      []string   `json:"acked"`
	Responders []string   `json:"responders"`
	ClipIDs    []string   `json:"clipIds"`
}

type Settings struct {
	ClipTTLHours int `json:"clipTtlHours"`
}

type state struct {
	Users    map[string]*User    `json:"users"`
	Channels map[string]*Channel `json:"channels"`
	Invites  map[string]*Invite  `json:"invites"`
	Clips    map[string]*Clip    `json:"clips"`
	SOS      map[string]*SOS     `json:"sos"`
	Settings Settings            `json:"settings"`
	NextTx   uint32              `json:"nextTx"`
}

type Store struct {
	mu   sync.RWMutex
	path string
	s    state
}

var ErrNotFound = errors.New("not found")

func OpenStore(dataDir string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(dataDir, "clips"), 0o700); err != nil {
		return nil, err
	}
	st := &Store{path: filepath.Join(dataDir, "state.json")}
	st.s = state{
		Users:    map[string]*User{},
		Channels: map[string]*Channel{},
		Invites:  map[string]*Invite{},
		Clips:    map[string]*Clip{},
		SOS:      map[string]*SOS{},
		Settings: Settings{ClipTTLHours: 24},
		NextTx:   1,
	}
	b, err := os.ReadFile(st.path)
	switch {
	case err == nil:
		if err := json.Unmarshal(b, &st.s); err != nil {
			return nil, fmt.Errorf("reading %s: %w", st.path, err)
		}
	case errors.Is(err, os.ErrNotExist):
	default:
		return nil, err
	}
	// Maps may be missing in older files.
	if st.s.Users == nil {
		st.s.Users = map[string]*User{}
	}
	if st.s.Channels == nil {
		st.s.Channels = map[string]*Channel{}
	}
	if st.s.Invites == nil {
		st.s.Invites = map[string]*Invite{}
	}
	if st.s.Clips == nil {
		st.s.Clips = map[string]*Clip{}
	}
	if st.s.SOS == nil {
		st.s.SOS = map[string]*SOS{}
	}
	if st.s.Settings.ClipTTLHours == 0 {
		st.s.Settings.ClipTTLHours = 24
	}
	if st.s.NextTx == 0 {
		st.s.NextTx = 1
	}
	// Exactly one default channel always exists.
	if st.defaultChannelLocked() == nil {
		c := &Channel{ID: newID(), Name: "Family", Color: "green", IsDefault: true, Members: []string{}, CreatedAt: time.Now().UTC()}
		st.s.Channels[c.ID] = c
		if err := st.saveLocked(); err != nil {
			return nil, err
		}
	}
	return st, nil
}

func (st *Store) saveLocked() error {
	b, err := json.MarshalIndent(&st.s, "", " ")
	if err != nil {
		return err
	}
	tmp := st.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, st.path)
}

// Update runs fn under the write lock and saves if fn returns nil.
func (st *Store) Update(fn func(s *state) error) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	if err := fn(&st.s); err != nil {
		return err
	}
	return st.saveLocked()
}

// View runs fn under the read lock.
func (st *Store) View(fn func(s *state)) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	fn(&st.s)
}

func (st *Store) defaultChannelLocked() *Channel {
	for _, c := range st.s.Channels {
		if c.IsDefault {
			return c
		}
	}
	return nil
}

func (s *state) defaultChannel() *Channel {
	for _, c := range s.Channels {
		if c.IsDefault {
			return c
		}
	}
	return nil
}

func (s *state) userByTokenHash(h string) *User {
	for _, u := range s.Users {
		if u.TokenHash == h {
			return u
		}
	}
	return nil
}

func (s *state) channelsOf(userID string) []*Channel {
	var out []*Channel
	for _, c := range s.Channels {
		if contains(c.Members, userID) {
			out = append(out, c)
		}
	}
	sortChannels(out)
	return out
}

func sortChannels(cs []*Channel) {
	sort.Slice(cs, func(i, j int) bool {
		if cs[i].IsDefault != cs[j].IsDefault {
			return cs[i].IsDefault
		}
		return cs[i].CreatedAt.Before(cs[j].CreatedAt)
	})
}

// removeUserEverywhere drops the user from every channel and deletes their
// personal channels. It returns the ids of clips whose files must be removed.
func (s *state) removeUserEverywhere(userID string) (deadClips []string) {
	for id, c := range s.Channels {
		if c.IsDirect() && contains(c.Members, userID) {
			delete(s.Channels, id)
			for cid, cl := range s.Clips {
				if cl.ChannelID == id {
					delete(s.Clips, cid)
					deadClips = append(deadClips, cid)
				}
			}
			continue
		}
		c.Members = remove(c.Members, userID)
	}
	delete(s.Users, userID)
	return deadClips
}

func (c *Channel) IsDirect() bool { return c.Kind == "direct" }

// canSee: members always; the admin also sees group channels (never direct ones).
func (c *Channel) canSee(u User) bool {
	return contains(c.Members, u.ID) || (u.Role == RoleAdmin && !c.IsDirect())
}

func (s *state) directBetween(a, b string) *Channel {
	for _, c := range s.Channels {
		if c.IsDirect() && len(c.Members) == 2 && contains(c.Members, a) && contains(c.Members, b) {
			return c
		}
	}
	return nil
}

func (s *state) takeTxID() uint32 {
	id := s.NextTx
	s.NextTx++
	if s.NextTx == 0 {
		s.NextTx = 1
	}
	return id
}

// ---- small helpers ----

func newID() string {
	b := make([]byte, 9)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func newToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// Crockford-style alphabet without I, L, O, U to avoid misreading.
var codeEnc = base32.NewEncoding("0123456789ABCDEFGHJKMNPQRSTVWXYZ").WithPadding(base32.NoPadding)

func newInviteCode() string {
	b := make([]byte, 7)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return codeEnc.EncodeToString(b)[:10]
}

func normalizeCode(c string) string {
	c = strings.ToUpper(strings.TrimSpace(c))
	c = strings.NewReplacer("-", "", " ", "", "O", "0", "I", "1", "L", "1").Replace(c)
	return c
}

func hashSecret(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func contains(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}

func remove(xs []string, x string) []string {
	out := xs[:0]
	for _, v := range xs {
		if v != x {
			out = append(out, v)
		}
	}
	return out
}

func addUnique(xs []string, x string) []string {
	if contains(xs, x) {
		return xs
	}
	return append(xs, x)
}
