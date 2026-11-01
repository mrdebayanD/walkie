package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

const (
	wsReadTimeout  = 10 * time.Minute // clients ping every 4 minutes
	wsWriteTimeout = 15 * time.Second
	wsHelloTimeout = 15 * time.Second
)

type clientMsg struct {
	T       string `json:"t"`
	Duty    *bool  `json:"duty,omitempty"`
	On      *bool  `json:"on,omitempty"`
	App     string `json:"app,omitempty"`
	Channel string `json:"channel,omitempty"`
	Mode    string `json:"mode,omitempty"`
	SosID   string `json:"sosId,omitempty"`
	Msg     string `json:"msg,omitempty"`
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request, u User) {
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// Native apps don't send Origin; browsers would, and are refused.
		CompressionMode: websocket.CompressionDisabled,
	})
	if err != nil {
		return
	}
	ws.SetReadLimit(32 << 10)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	c := &conn{userID: u.ID, name: u.Name, send: make(chan outMsg, 512)}
	c.kill = func() {
		cancel()
		_ = ws.CloseNow()
	}

	// First message must be hello.
	hctx, hcancel := context.WithTimeout(ctx, wsHelloTimeout)
	typ, data, err := ws.Read(hctx)
	hcancel()
	if err != nil || typ != websocket.MessageText {
		ws.Close(websocket.StatusPolicyViolation, "hello required")
		return
	}
	var hello clientMsg
	if json.Unmarshal(data, &hello) != nil || hello.T != "hello" {
		ws.Close(websocket.StatusPolicyViolation, "hello required")
		return
	}
	c.duty = hello.Duty != nil && *hello.Duty

	// Writer goroutine.
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		for m := range c.send {
			wctx, wcancel := context.WithTimeout(ctx, wsWriteTimeout)
			t := websocket.MessageText
			if m.binary {
				t = websocket.MessageBinary
			}
			err := ws.Write(wctx, t, m.data)
			wcancel()
			if err != nil {
				c.kill()
				// Drain so senders never block.
				for range c.send {
				}
				return
			}
		}
	}()

	s.hub.register(c)
	defer func() {
		s.hub.unregister(c)
		<-writerDone
		ws.Close(websocket.StatusNormalClosure, "")
	}()

	for {
		rctx, rcancel := context.WithTimeout(ctx, wsReadTimeout)
		typ, data, err := ws.Read(rctx)
		rcancel()
		if err != nil {
			if !errors.Is(err, context.Canceled) && websocket.CloseStatus(err) == -1 {
				s.logf("ws read %s: %v", u.ID, err)
			}
			return
		}
		if typ == websocket.MessageBinary {
			s.hub.audio(c, data)
			continue
		}
		var m clientMsg
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		switch m.T {
		case "ping":
			s.hub.mu.Lock()
			s.hub.sendLocked(c, encode(map[string]string{"t": "pong"}))
			s.hub.mu.Unlock()
		case "duty":
			if m.On != nil {
				s.hub.setDuty(c, *m.On)
			}
		case "floor_request":
			s.hub.requestFloor(c, m.Channel, m.Mode, m.SosID)
		case "floor_release":
			s.hub.releaseFloor(c)
		case "hello":
			if m.Duty != nil {
				s.hub.setDuty(c, *m.Duty)
			}
		case "log":
			// Diagnostics from the app (e.g. microphone or encoder problems).
			msg := m.Msg
			if len(msg) > 300 {
				msg = msg[:300]
			}
			s.logf("app log %s (%s): %s", u.Name, hello.App, msg)
		}
	}
}
