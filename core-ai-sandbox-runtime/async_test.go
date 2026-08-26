package main

import "testing"

func TestIsAsync(t *testing.T) {
	cases := []struct {
		name string
		args string
		want bool
	}{
		{"async true", `{"async": true}`, true},
		{"async false", `{"async": false}`, false},
		{"run_in_background true", `{"run_in_background": true}`, true},
		{"run_in_background false", `{"run_in_background": false}`, false},
		{"both true", `{"async": true, "run_in_background": true}`, true},
		{"neither", `{"command": "ls"}`, false},
		{"non bool value", `{"async": "true"}`, false},
		{"invalid json", `not json`, false},
		{"empty object", `{}`, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := isAsync(c.args); got != c.want {
				t.Fatalf("isAsync(%q) = %v, want %v", c.args, got, c.want)
			}
		})
	}
}
