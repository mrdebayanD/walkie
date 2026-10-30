package app

import "sort"

// Version is the server version reported by GET /v1/me.
const Version = "1.0.0"

func sortUsers(us []UserView) {
	sort.Slice(us, func(i, j int) bool {
		if us[i].Status != us[j].Status {
			return us[i].Status == StatusPending
		}
		return us[i].Name < us[j].Name
	})
}
