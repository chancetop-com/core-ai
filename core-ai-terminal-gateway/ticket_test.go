package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strings"
	"testing"
)

const fixtureSecret = "test-secret-0123456789abcdef"

// fixtureTicketString is copied verbatim from the Java side's locked fixture
// assertion: TerminalTicketCodecTest.mintProducesTheLockedFixtureString() in
// core-ai-server. This is the cross-stack contract: a codec change on either
// side that breaks this literal breaks that side's test.
const fixtureTicketString = "eyJzaWQiOiJzLWZpeHR1cmUiLCJzYmlkIjoic2ItZml4dHVyZSIsImNpZCI6ImMtZml4dHVyZSIsImlwIjoiMTAuMC4wLjkiLCJwb3J0Ijo4MDgwLCJpYXQiOjE3NTY3MDAwMDAsImV4cCI6MTc1NjcwMDAzMCwibm9uY2UiOiIwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiJ9.Bt5LGyUd_VDUJkSaky8ajkX8humRHZhDwpUyRhjZIIM"

var fixtureTicket = Ticket{
	Sid:   "s-fixture",
	Sbid:  "sb-fixture",
	Cid:   "c-fixture",
	IP:    "10.0.0.9",
	Port:  8080,
	Iat:   1756700000,
	Exp:   1756700030,
	Nonce: "0123456789abcdef0123456789abcdef",
}

func TestVerifyTicketAcceptsTheLockedFixture(t *testing.T) {
	ticket, err := VerifyTicket(fixtureTicketString, []byte(fixtureSecret), 1756700010)
	if err != nil {
		t.Fatalf("expected the fixture ticket to verify, got error: %v", err)
	}
	if ticket != fixtureTicket {
		t.Fatalf("verified ticket mismatch: got %+v, want %+v", ticket, fixtureTicket)
	}
}

// signPayload signs an arbitrary raw payload string exactly as
// TerminalTicketCodec.mint would, without going through the Ticket struct —
// used both by mintTicketForTest (a well-formed payload) and by tests that
// need a signed-but-malformed/incomplete payload.
func signPayload(payload string, secret []byte) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(payload))
	signature := mac.Sum(nil)
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." + base64.RawURLEncoding.EncodeToString(signature)
}

// mintTicketForTest is a test-only Go mint helper (production mint stays
// Java-side, per TerminalTicketCodec.mint); it exists solely to prove the
// two languages agree byte-for-byte on the wire format.
func mintTicketForTest(t Ticket, secret []byte) string {
	payload := fmt.Sprintf(
		`{"sid":"%s","sbid":"%s","cid":"%s","ip":"%s","port":%d,"iat":%d,"exp":%d,"nonce":"%s"}`,
		t.Sid, t.Sbid, t.Cid, t.IP, t.Port, t.Iat, t.Exp, t.Nonce)
	return signPayload(payload, secret)
}

func TestGoMintAgreesWithJavaFixtureByteForByte(t *testing.T) {
	got := mintTicketForTest(fixtureTicket, []byte(fixtureSecret))
	if got != fixtureTicketString {
		t.Fatalf("go mint does not match the java fixture:\n got:  %s\n want: %s", got, fixtureTicketString)
	}
}

func TestVerifyTicketRejectsExpired(t *testing.T) {
	// now == exp must be rejected (exp <= now), matching the Java side.
	if ticket, err := VerifyTicket(fixtureTicketString, []byte(fixtureSecret), fixtureTicket.Exp); err == nil {
		t.Fatalf("expected expiry rejection, got ticket: %+v", ticket)
	}
}

func TestVerifyTicketRejectsTamperedPayload(t *testing.T) {
	parts := strings.SplitN(fixtureTicketString, ".", 2)
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatalf("failed to decode fixture payload: %v", err)
	}
	tampered := strings.Replace(string(payloadBytes), `"sid":"s-fixture"`, `"sid":"s-tampered"`, 1)
	tamperedTicket := base64.RawURLEncoding.EncodeToString([]byte(tampered)) + "." + parts[1]

	if ticket, err := VerifyTicket(tamperedTicket, []byte(fixtureSecret), 0); err == nil {
		t.Fatalf("expected tampered payload to be rejected, got ticket: %+v", ticket)
	}
}

func TestVerifyTicketRejectsMissingRequiredField(t *testing.T) {
	// Validly signed but "sid" is absent entirely: encoding/json would
	// zero-value it to "" silently without an explicit post-unmarshal check.
	payload := `{"sbid":"sb-1","cid":"cid-1","ip":"10.1.2.3","port":8090,"iat":1756700100,"exp":1756700130,"nonce":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}`
	ticketString := signPayload(payload, []byte(fixtureSecret))

	if ticket, err := VerifyTicket(ticketString, []byte(fixtureSecret), 0); err == nil {
		t.Fatalf("expected ticket missing 'sid' to be rejected, got ticket: %+v", ticket)
	}
}

func TestVerifyTicketRejectsGarbage(t *testing.T) {
	cases := []string{
		"not-a-ticket-at-all",
		"!!!.!!!",
		"a.b.c",
		"",
	}
	for _, c := range cases {
		if ticket, err := VerifyTicket(c, []byte(fixtureSecret), 0); err == nil {
			t.Fatalf("expected garbage ticket %q to be rejected, got ticket: %+v", c, ticket)
		}
	}
}
