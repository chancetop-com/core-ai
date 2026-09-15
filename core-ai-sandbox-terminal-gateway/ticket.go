// Package main implements the sandbox terminal gateway: a ticketed
// WebSocket proxy sitting between the browser and the cluster-internal
// sandbox runtime. This file owns the ticket wire format shared with
// core-ai-server (ai.core.server.sandbox.terminal.TerminalTicketCodec).
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Ticket mirrors ai.core.server.sandbox.terminal.TerminalTicket. Field order
// here (and the json tags) match the key order core-ai-server's
// TerminalTicketCodec.mint emits, though verification below does not depend
// on that order: only the HMAC signature protects payload integrity.
type Ticket struct {
	Sid   string `json:"sid"`
	Sbid  string `json:"sbid"`
	Cid   string `json:"cid"`
	IP    string `json:"ip"`
	Port  int    `json:"port"`
	Iat   int64  `json:"iat"`
	Exp   int64  `json:"exp"`
	Nonce string `json:"nonce"`
}

// VerifyTicket parses and verifies a ticket string minted by
// ai.core.server.sandbox.terminal.TerminalTicketCodec.mint:
//
//	base64url_nopad(payloadJson) + "." + base64url_nopad(hmacSha256(payloadJsonBytes, secret))
//
// It rejects any structural, base64, signature, or expiry failure. now is
// epoch seconds; a ticket is expired once its exp is less than or equal to
// now.
func VerifyTicket(ticketString string, secret []byte, now int64) (Ticket, error) {
	parts := strings.Split(ticketString, ".")
	if len(parts) != 2 {
		return Ticket{}, errors.New("malformed ticket structure, expected exactly one '.'")
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return Ticket{}, fmt.Errorf("malformed ticket payload encoding: %w", err)
	}
	signatureBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Ticket{}, fmt.Errorf("malformed ticket signature encoding: %w", err)
	}

	mac := hmac.New(sha256.New, secret)
	mac.Write(payloadBytes)
	expectedSignature := mac.Sum(nil)
	if !hmac.Equal(expectedSignature, signatureBytes) {
		return Ticket{}, errors.New("ticket signature mismatch")
	}

	var ticket Ticket
	if err := json.Unmarshal(payloadBytes, &ticket); err != nil {
		return Ticket{}, fmt.Errorf("malformed ticket payload: %w", err)
	}
	if err := validateTicketFields(ticket); err != nil {
		return Ticket{}, err
	}
	if ticket.Exp <= now {
		return Ticket{}, errors.New("ticket expired")
	}
	return ticket, nil
}

// validateTicketFields rejects a validly-signed payload that is missing a
// required key. encoding/json silently zero-values an absent JSON field
// instead of erroring, so without this check a signed-but-incomplete
// payload (e.g. no "sid") would otherwise verify successfully.
func validateTicketFields(t Ticket) error {
	if t.Sid == "" || t.Sbid == "" || t.Cid == "" || t.IP == "" || t.Nonce == "" {
		return errors.New("ticket payload missing a required string field")
	}
	if t.Port <= 0 || t.Iat <= 0 || t.Exp <= 0 {
		return errors.New("ticket payload missing a required numeric field")
	}
	return nil
}
