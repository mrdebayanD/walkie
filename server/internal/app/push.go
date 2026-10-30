package app

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type PushKind int

const (
	PushTx PushKind = iota + 1
	PushSOS
	PushSOSResolved
)

type PushMessage struct {
	Kind       PushKind
	Channel    string
	TxID       uint32
	Speaker    string // talker or SOS sender name
	SOSID      string
	Resolution string
	Repeat     bool
}

// Pusher delivers a message to one user's devices. Returning ErrTokenGone
// means the device token is dead and should be forgotten.
type Pusher interface {
	PushAPNsPTT(token string, sandbox bool, m PushMessage) error
	PushAPNsAlert(token string, sandbox bool, m PushMessage) error
	PushFCM(token string, m PushMessage) error
}

var ErrTokenGone = errors.New("push token no longer valid")

func (s *Server) sendPush(u User, m PushMessage) {
	go func() {
		switch u.Platform {
		case "ios":
			var err error
			if m.Kind == PushTx {
				if u.Devices.APNsPTT == "" {
					return
				}
				err = s.push.PushAPNsPTT(u.Devices.APNsPTT, u.Devices.APNsSandbox, m)
				if errors.Is(err, ErrTokenGone) {
					s.clearToken(u.ID, "apnsPtt")
				}
			} else {
				if u.Devices.APNsAlert == "" {
					return
				}
				err = s.push.PushAPNsAlert(u.Devices.APNsAlert, u.Devices.APNsSandbox, m)
				if errors.Is(err, ErrTokenGone) {
					s.clearToken(u.ID, "apnsAlert")
				}
			}
			if err != nil && !errors.Is(err, ErrTokenGone) && !errors.Is(err, errPushDisabled) {
				s.logf("apns push to %s failed: %v", u.ID, err)
			}
		case "android":
			if u.Devices.FCM == "" {
				return
			}
			err := s.push.PushFCM(u.Devices.FCM, m)
			if errors.Is(err, ErrTokenGone) {
				s.clearToken(u.ID, "fcm")
			} else if err != nil && !errors.Is(err, errPushDisabled) {
				s.logf("fcm push to %s failed: %v", u.ID, err)
			}
		}
	}()
}

func (s *Server) clearToken(userID, which string) {
	_ = s.store.Update(func(st *state) error {
		u := st.Users[userID]
		if u == nil {
			return nil
		}
		switch which {
		case "apnsPtt":
			u.Devices.APNsPTT = ""
		case "apnsAlert":
			u.Devices.APNsAlert = ""
		case "fcm":
			u.Devices.FCM = ""
		}
		return nil
	})
}

var errPushDisabled = errors.New("push not configured")

// ---- real implementation ----

type APNsConfig struct {
	KeyFile  string // AuthKey_XXXX.p8
	KeyID    string
	TeamID   string
	BundleID string // e.g. com.debayandatta.walkie
	Critical bool   // app has Apple's Critical Alerts entitlement
}

type FCMConfig struct {
	ServiceAccountFile string // Firebase service account JSON
}

type realPusher struct {
	http *http.Client

	apns    *APNsConfig
	apnsKey *ecdsa.PrivateKey
	apnsMu  sync.Mutex
	apnsJWT string
	apnsAt  time.Time

	fcm       *fcmAccount
	fcmMu     sync.Mutex
	fcmToken  string
	fcmExpiry time.Time
}

type fcmAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
	key         *rsa.PrivateKey
}

// NewPusher builds a pusher; missing configs just disable that platform.
func NewPusher(a *APNsConfig, f *FCMConfig) Pusher {
	p := &realPusher{http: &http.Client{Timeout: 15 * time.Second}}
	if a != nil {
		key, err := loadP8(a.KeyFile)
		if err != nil {
			fmt.Fprintf(os.Stderr, "APNs disabled: %v\n", err)
		} else {
			p.apns, p.apnsKey = a, key
		}
	}
	if f != nil {
		acc, err := loadServiceAccount(f.ServiceAccountFile)
		if err != nil {
			fmt.Fprintf(os.Stderr, "FCM disabled: %v\n", err)
		} else {
			p.fcm = acc
		}
	}
	return p
}

func loadP8(path string) (*ecdsa.PrivateKey, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		return nil, errors.New("APNs key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, err
	}
	ek, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("APNs key is not an EC key")
	}
	return ek, nil
}

func loadServiceAccount(path string) (*fcmAccount, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var a fcmAccount
	if err := json.Unmarshal(b, &a); err != nil {
		return nil, err
	}
	blk, _ := pem.Decode([]byte(a.PrivateKey))
	if blk == nil {
		return nil, errors.New("service account key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, err
	}
	rk, ok := k.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("service account key is not RSA")
	}
	a.key = rk
	if a.TokenURI == "" {
		a.TokenURI = "https://oauth2.googleapis.com/token"
	}
	if a.ProjectID == "" {
		return nil, errors.New("service account has no project_id")
	}
	return &a, nil
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func signJWT(header, claims map[string]any, sign func(digest []byte) ([]byte, error)) (string, error) {
	hb, _ := json.Marshal(header)
	cb, _ := json.Marshal(claims)
	unsigned := b64(hb) + "." + b64(cb)
	d := sha256.Sum256([]byte(unsigned))
	sig, err := sign(d[:])
	if err != nil {
		return "", err
	}
	return unsigned + "." + b64(sig), nil
}

func (p *realPusher) apnsToken() (string, error) {
	p.apnsMu.Lock()
	defer p.apnsMu.Unlock()
	if p.apnsJWT != "" && time.Since(p.apnsAt) < 40*time.Minute {
		return p.apnsJWT, nil
	}
	now := time.Now()
	tok, err := signJWT(
		map[string]any{"alg": "ES256", "kid": p.apns.KeyID},
		map[string]any{"iss": p.apns.TeamID, "iat": now.Unix()},
		func(d []byte) ([]byte, error) {
			r, s, err := ecdsa.Sign(rand.Reader, p.apnsKey, d)
			if err != nil {
				return nil, err
			}
			out := make([]byte, 64)
			r.FillBytes(out[:32])
			s.FillBytes(out[32:])
			return out, nil
		})
	if err != nil {
		return "", err
	}
	p.apnsJWT, p.apnsAt = tok, now
	return tok, nil
}

func (p *realPusher) apnsSend(token string, sandbox bool, pushType, topic string, priority int, expiration int64, payload any) error {
	if p.apns == nil {
		return errPushDisabled
	}
	jwt, err := p.apnsToken()
	if err != nil {
		return err
	}
	host := "https://api.push.apple.com"
	if sandbox {
		host = "https://api.sandbox.push.apple.com"
	}
	body, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", host+"/3/device/"+url.PathEscape(token), bytes.NewReader(body))
	req.Header.Set("authorization", "bearer "+jwt)
	req.Header.Set("apns-push-type", pushType)
	req.Header.Set("apns-topic", topic)
	req.Header.Set("apns-priority", strconv.Itoa(priority))
	req.Header.Set("apns-expiration", strconv.FormatInt(expiration, 10))
	resp, err := p.http.Do(req) // net/http negotiates HTTP/2, which APNs requires
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 200 {
		return nil
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode == 410 || strings.Contains(string(b), "BadDeviceToken") || strings.Contains(string(b), "Unregistered") {
		return ErrTokenGone
	}
	if resp.StatusCode == 403 && strings.Contains(string(b), "ExpiredProviderToken") {
		p.apnsMu.Lock()
		p.apnsJWT = ""
		p.apnsMu.Unlock()
	}
	return fmt.Errorf("apns %d: %s", resp.StatusCode, b)
}

func (p *realPusher) PushAPNsPTT(token string, sandbox bool, m PushMessage) error {
	if p.apns == nil {
		return errPushDisabled
	}
	payload := map[string]any{"aps": map[string]any{}, "type": "tx", "channel": m.Channel, "txId": m.TxID, "speaker": m.Speaker}
	// PTT pushes must be delivered now or never.
	return p.apnsSend(token, sandbox, "pushtotalk", p.apns.BundleID+".voip-ptt", 10, 0, payload)
}

func (p *realPusher) PushAPNsAlert(token string, sandbox bool, m PushMessage) error {
	if p.apns == nil {
		return errPushDisabled
	}
	var aps map[string]any
	switch m.Kind {
	case PushSOS:
		level := "time-sensitive"
		sound := any("default")
		if p.apns.Critical {
			level = "critical"
			sound = map[string]any{"critical": 1, "name": "default", "volume": 1.0}
		}
		aps = map[string]any{
			"alert":              map[string]string{"title": "SOS from " + m.Speaker, "body": "Tap to see where they are and call them."},
			"sound":              sound,
			"interruption-level": level,
			"thread-id":          "sos-" + m.SOSID,
			"category":           "SOS",
		}
	case PushSOSResolved:
		body := m.Speaker + " is safe."
		if m.Resolution == "false_alarm" {
			body = m.Speaker + "'s SOS was a false alarm."
		}
		aps = map[string]any{
			"alert":              map[string]string{"title": "SOS closed", "body": body},
			"sound":              "default",
			"interruption-level": "time-sensitive",
			"thread-id":          "sos-" + m.SOSID,
		}
	default:
		return nil
	}
	payload := map[string]any{"aps": aps, "type": kindName(m.Kind), "sosId": m.SOSID}
	return p.apnsSend(token, sandbox, "alert", p.apns.BundleID, 10, time.Now().Add(10*time.Minute).Unix(), payload)
}

func kindName(k PushKind) string {
	switch k {
	case PushTx:
		return "tx"
	case PushSOS:
		return "sos"
	case PushSOSResolved:
		return "sos_resolved"
	}
	return ""
}

func (p *realPusher) fcmAccessToken() (string, error) {
	p.fcmMu.Lock()
	defer p.fcmMu.Unlock()
	if p.fcmToken != "" && time.Until(p.fcmExpiry) > 5*time.Minute {
		return p.fcmToken, nil
	}
	now := time.Now()
	assertion, err := signJWT(
		map[string]any{"alg": "RS256", "typ": "JWT"},
		map[string]any{"iss": p.fcm.ClientEmail, "scope": "https://www.googleapis.com/auth/firebase.messaging", "aud": p.fcm.TokenURI, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()},
		func(d []byte) ([]byte, error) { return rsa.SignPKCS1v15(rand.Reader, p.fcm.key, crypto.SHA256, d) })
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {assertion}}
	resp, err := p.http.PostForm(p.fcm.TokenURI, form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<16)).Decode(&out); err != nil || out.AccessToken == "" {
		return "", fmt.Errorf("google token exchange failed (%d)", resp.StatusCode)
	}
	p.fcmToken = out.AccessToken
	p.fcmExpiry = now.Add(time.Duration(out.ExpiresIn) * time.Second)
	return p.fcmToken, nil
}

func (p *realPusher) PushFCM(token string, m PushMessage) error {
	if p.fcm == nil {
		return errPushDisabled
	}
	at, err := p.fcmAccessToken()
	if err != nil {
		return err
	}
	data := map[string]string{"type": kindName(m.Kind)}
	ttl := "30s"
	switch m.Kind {
	case PushTx:
		data["channel"] = m.Channel
		data["txId"] = strconv.FormatUint(uint64(m.TxID), 10)
		data["speaker"] = m.Speaker
		ttl = "20s"
	case PushSOS:
		data["sosId"] = m.SOSID
		data["sender"] = m.Speaker
		ttl = "600s"
	case PushSOSResolved:
		data["sosId"] = m.SOSID
		data["sender"] = m.Speaker
		data["resolution"] = m.Resolution
		ttl = "3600s"
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token":   token,
		"data":    data,
		"android": map[string]any{"priority": "high", "ttl": ttl},
	}})
	req, _ := http.NewRequest("POST", "https://fcm.googleapis.com/v1/projects/"+url.PathEscape(p.fcm.ProjectID)+"/messages:send", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+at)
	req.Header.Set("Content-Type", "application/json")
	resp, err := p.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 200 {
		return nil
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode == 404 || strings.Contains(string(b), "UNREGISTERED") {
		return ErrTokenGone
	}
	return fmt.Errorf("fcm %d: %s", resp.StatusCode, b)
}
