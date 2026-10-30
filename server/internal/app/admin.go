package app

import (
	"os"
	"path/filepath"
	"strings"
	"time"
)

// EnsureAdminInvite makes sure a brand-new server can be claimed: if nobody
// is admin yet, it keeps one valid admin invite and saves its link to
// data/ADMIN_INVITE.txt (readable only by the server's user).
func (s *Server) EnsureAdminInvite() (link string, created bool, err error) {
	hasAdmin := false
	s.store.View(func(st *state) {
		for _, u := range st.Users {
			if u.Role == RoleAdmin {
				hasAdmin = true
			}
		}
	})
	file := filepath.Join(s.cfg.DataDir, "ADMIN_INVITE.txt")
	if hasAdmin {
		_ = os.Remove(file)
		return "", false, nil
	}
	if b, err := os.ReadFile(file); err == nil {
		lines := strings.Split(strings.TrimSpace(string(b)), "\n")
		code := strings.TrimSpace(lines[len(lines)-1])
		valid := false
		h := hashSecret(normalizeCode(code))
		s.store.View(func(st *state) {
			for _, inv := range st.Invites {
				if inv.Admin && inv.CodeHash == h && inv.UsedBy == "" && time.Now().Before(inv.ExpiresAt) {
					valid = true
				}
			}
		})
		if valid {
			return s.cfg.PublicURL + "/join?c=" + code, true, nil
		}
	}
	code, link, _, err := s.CreateAdminInvite()
	if err != nil {
		return "", false, err
	}
	if err := os.WriteFile(file, []byte(link+"\n"+code+"\n"), 0o600); err != nil {
		return "", false, err
	}
	return link, true, nil
}
