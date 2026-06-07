// Package categorize is a faithful Go port of categorize.ts.
// It normalizes merchant names, builds dedupe hashes, and resolves categories.
package categorize

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"

	"github.com/himanshu/ledger-api/internal/model"
)

// ---------- merchant normalization ----------

var (
	reTrailingJunk = regexp.MustCompile(`[*#@].*$`)
	reNoise        = regexp.MustCompile(`(?i)\b(pvt|ltd|limited|india|payments?|upi|com|in)\b`)
	reNonAlpha     = regexp.MustCompile(`[^a-z0-9 ]`)
	reSpaces       = regexp.MustCompile(`\s+`)
)

// NormalizeMerchant strips noise so "AMAZON PAY *ORDER 123" and "Amazon Pay" collapse.
// Mirrors normalizeMerchant() in categorize.ts.
func NormalizeMerchant(raw string) string {
	if raw == "" {
		return "unknown"
	}
	s := strings.ToLower(raw)
	s = reTrailingJunk.ReplaceAllString(s, "")
	s = reNoise.ReplaceAllString(s, "")
	s = reNonAlpha.ReplaceAllString(s, " ")
	s = strings.TrimSpace(reSpaces.ReplaceAllString(s, " "))
	if len(s) > 40 {
		s = s[:40]
	}
	if s == "" {
		return "unknown"
	}
	return s
}

// ---------- dedupe hash ----------

// DedupeHash builds a stable SHA-256 fingerprint for a transaction.
// Mirrors dedupeHash() in categorize.ts with identical key-selection logic:
//
//   - ref-based when a UPI/bank reference ID is present (cross-source dedup)
//   - merchant-based otherwise (if merchant is known)
//   - fallback-ID-based when neither is available (keeps distinct messages distinct)
func DedupeHash(amount float64, date, merchantNormalized string, referenceID *string, direction, fallbackID string) string {
	var basis string
	switch {
	case referenceID != nil && *referenceID != "":
		basis = fmt.Sprintf("ref|%s|%.2f|%s", *referenceID, amount, direction)
	case merchantNormalized != "" && merchantNormalized != "unknown":
		basis = fmt.Sprintf("%.2f|%s|%s|%s", amount, date, merchantNormalized, direction)
	default:
		fb := fallbackID
		if fb == "" {
			fb = randomHex()
		}
		basis = fmt.Sprintf("%.2f|%s|%s|%s", amount, date, direction, fb)
	}
	h := sha256.Sum256([]byte(basis))
	return hex.EncodeToString(h[:])
}

func randomHex() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ---------- keyword categorizer ----------

// kwEntry pairs a category with its compiled pattern.
type kwEntry struct {
	cat string
	re  *regexp.Regexp
}

var kwList = []kwEntry{
	{"Food & Dining", regexp.MustCompile(`(?i)swiggy|zomato|restaurant|cafe|coffee|dominos|mcdonald|kfc|eatery|dhaba`)},
	{"Groceries", regexp.MustCompile(`(?i)bigbasket|blinkit|zepto|dmart|grofers|grocery|supermarket|instamart`)},
	{"Transport", regexp.MustCompile(`(?i)uber|ola|rapido|irctc|petrol|fuel|fastag|metro|redbus|indrive`)},
	{"Shopping", regexp.MustCompile(`(?i)amazon|flipkart|myntra|ajio|meesho|nykaa|store|mart|retail`)},
	{"Subscriptions", regexp.MustCompile(`(?i)netflix|spotify|prime|hotstar|youtube|jiocinema|subscription|icloud|adobe`)},
	{"Bills & Utilities", regexp.MustCompile(`(?i)electricity|recharge|jio|airtel|vi |broadband|gas|water|bill|dth`)},
	{"Entertainment", regexp.MustCompile(`(?i)bookmyshow|pvr|inox|cinema|game|steam`)},
	{"Health", regexp.MustCompile(`(?i)pharmacy|apollo|medplus|hospital|clinic|1mg|pharmeasy|tata 1mg`)},
}

// KeywordCategory returns a category inferred from merchant text, or "" when no match.
func KeywordCategory(merchant string) string {
	for _, e := range kwList {
		if e.re.MatchString(merchant) {
			return e.cat
		}
	}
	return ""
}

// ---------- category resolution ----------

// ResolveCategory applies the full resolution chain from categorize.ts:
//
//  1. User's saved merchant rule (highest priority)
//  2. Credit direction → Income
//  3. LLM/parser-supplied category (if not Uncategorized)
//  4. Keyword heuristic
//  5. Uncategorized
func ResolveCategory(parsed model.ParsedTransaction, merchantNormalized string, userRules map[string]string) string {
	if cat, ok := userRules[merchantNormalized]; ok {
		return cat
	}
	if parsed.Direction == model.Credit {
		return "Income"
	}
	if parsed.Category != "" && parsed.Category != "Uncategorized" {
		return parsed.Category
	}
	merchant := ""
	if parsed.Merchant != nil {
		merchant = *parsed.Merchant
	}
	if cat := KeywordCategory(merchant); cat != "" {
		return cat
	}
	return "Uncategorized"
}
