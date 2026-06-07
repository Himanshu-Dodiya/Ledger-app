package categorize

import (
	"testing"

	"github.com/himanshu/ledger-api/internal/model"
)

func TestNormalizeMerchant(t *testing.T) {
	cases := []struct{ in, want string }{
		{"AMAZON PAY *ORDER 123", "amazon pay"},
		{"Amazon Pay", "amazon pay"},
		{"Swiggy India Ltd", "swiggy"},
		{"", "unknown"},
		{"@junk", "unknown"},
		{"Zomato Payments Pvt Ltd", "zomato"},
	}
	for _, tc := range cases {
		got := NormalizeMerchant(tc.in)
		if got != tc.want {
			t.Errorf("NormalizeMerchant(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestDedupeHash(t *testing.T) {
	ref := "REF123456"
	// ref-based: same result regardless of merchant/date
	h1 := DedupeHash(500, "2025-06-01", "amazon", &ref, "debit", "")
	h2 := DedupeHash(500, "2025-06-02", "flipkart", &ref, "debit", "msg-abc")
	if h1 != h2 {
		t.Errorf("ref-based hashes differ: %s vs %s", h1, h2)
	}

	// merchant-based: different merchants → different hashes
	h3 := DedupeHash(500, "2025-06-01", "amazon", nil, "debit", "")
	h4 := DedupeHash(500, "2025-06-01", "flipkart", nil, "debit", "")
	if h3 == h4 {
		t.Errorf("merchant-based hashes should differ")
	}

	// fallback: same fallbackID → same hash
	h5 := DedupeHash(500, "2025-06-01", "unknown", nil, "debit", "gmail-abc")
	h6 := DedupeHash(500, "2025-06-01", "unknown", nil, "debit", "gmail-abc")
	if h5 != h6 {
		t.Errorf("fallback hashes should match: %s vs %s", h5, h6)
	}

	// fallback: different fallbackIDs → different hashes
	h7 := DedupeHash(500, "2025-06-01", "unknown", nil, "debit", "gmail-abc")
	h8 := DedupeHash(500, "2025-06-01", "unknown", nil, "debit", "gmail-xyz")
	if h7 == h8 {
		t.Errorf("fallback hashes with different IDs should differ")
	}
}

func TestKeywordCategory(t *testing.T) {
	cases := []struct{ in, want string }{
		{"swiggy", "Food & Dining"},
		{"ZOMATO", "Food & Dining"},
		{"Uber", "Transport"},
		{"netflix subscription", "Subscriptions"},
		{"apollo pharmacy", "Health"},
		{"amazon retail", "Shopping"},
		{"unknown merchant xyz", ""},
	}
	for _, tc := range cases {
		got := KeywordCategory(tc.in)
		if got != tc.want {
			t.Errorf("KeywordCategory(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestResolveCategory(t *testing.T) {
	pf := func(v float64) *float64 { return &v }
	ps := func(v string) *string { return &v }

	userRules := map[string]string{"amazon pay": "Shopping"}

	// user rule wins
	got := ResolveCategory(model.ParsedTransaction{Direction: model.Debit, Category: "Food & Dining", Amount: pf(100), Merchant: ps("AMAZON PAY")}, "amazon pay", userRules)
	if got != "Shopping" {
		t.Errorf("user rule: got %q, want Shopping", got)
	}

	// credit → Income (beats keyword)
	got = ResolveCategory(model.ParsedTransaction{Direction: model.Credit, Category: "Uncategorized", Amount: pf(100), Merchant: ps("swiggy")}, "swiggy", map[string]string{})
	if got != "Income" {
		t.Errorf("credit direction: got %q, want Income", got)
	}

	// LLM/parsed category used
	got = ResolveCategory(model.ParsedTransaction{Direction: model.Debit, Category: "Health", Amount: pf(100)}, "genericmed", map[string]string{})
	if got != "Health" {
		t.Errorf("parsed category: got %q, want Health", got)
	}

	// keyword fallback
	got = ResolveCategory(model.ParsedTransaction{Direction: model.Debit, Category: "Uncategorized", Amount: pf(100), Merchant: ps("netflix")}, "netflix", map[string]string{})
	if got != "Subscriptions" {
		t.Errorf("keyword: got %q, want Subscriptions", got)
	}

	// final fallback
	got = ResolveCategory(model.ParsedTransaction{Direction: model.Debit, Category: "Uncategorized", Amount: pf(100)}, "unknown", map[string]string{})
	if got != "Uncategorized" {
		t.Errorf("fallback: got %q, want Uncategorized", got)
	}
}
