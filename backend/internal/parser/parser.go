// Package parser is a faithful Go port of the TypeScript parser (src/lib/parser.ts).
// It extracts structured transaction fields from Indian bank/UPI SMS and email text.
//
// RE2 note: Go's regexp package uses RE2, which has no lookaheads. The TypeScript
// MERCHANT_RES patterns that used positive lookaheads (?=...) are rewritten here as
// "lazy capture + required non-capturing terminator": the merchant group uses a lazy
// quantifier and the terminator is consumed but not captured, yielding the same result.
package parser

import (
	"regexp"
	"strconv"
	"strings"

	"github.com/himanshu/ledger-api/internal/model"
)

// ---------- pre-filter ----------

var (
	reLikely = regexp.MustCompile(`(?i)(debited?|credited?|spent|paid|payment|transaction|txn|purchase|received|withdrawn|charged|debit|credit)`)
	reMoney  = regexp.MustCompile(`(?i)(₹|rs\.?|inr)\s?[\d,]+(\.\d{1,2})?`)
	reSkip   = regexp.MustCompile(`(?i)(otp|one[-\s]?time\s*password|statement\s+is\s+ready|e-?statement|password|verify\s+your|promotional|offer\s+ends|sale\s+is\s+live)`)
)

// MightBeFinancial is the loose gate: requires a transactional verb but NOT a currency
// symbol. Used to decide whether to spend an LLM call on a message that regex missed
// (e.g. SBI UPI's "debited by 500.0" with no symbol).
func MightBeFinancial(subject, body string) bool {
	text := subject + "\n" + body
	return !reSkip.MatchString(text) && reLikely.MatchString(text)
}

// LooksTransactional is the strict gate: requires both a verb and a currency token.
func LooksTransactional(subject, body string) bool {
	text := subject + "\n" + body
	return !reSkip.MatchString(text) && reLikely.MatchString(text) && reMoney.MatchString(text)
}

// ---------- amount ----------

var amountREs = []*regexp.Regexp{
	// currency prefix: ₹500, Rs.1,234.50, INR 500
	regexp.MustCompile(`(?i)(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)`),
	// verb (+ optional preposition + optional currency prefix)
	regexp.MustCompile(`(?i)\b(?:debited|credited|debit|credit|deducted|spent|paid|charged|withdrawn|received)\s+(?:by|with|for|of)?\s*(?:₹|rs\.?|inr)?\s*([\d,]+(?:\.\d{1,2})?)`),
	// label: "Amount: 1,234.00" / "Amt 500"
	regexp.MustCompile(`(?i)\b(?:amount|amt)\b\s*(?:of)?\s*[:\-]?\s*(?:₹|rs\.?|inr)?\s*([\d,]+(?:\.\d{1,2})?)`),
	// suffix currency: "1,234.00 INR" / "500 Rs"
	regexp.MustCompile(`(?i)([\d,]+(?:\.\d{1,2})?)\s*(?:₹|rs\.?|inr)\b`),
}

func parseAmount(text string) (float64, bool) {
	for _, re := range amountREs {
		if m := re.FindStringSubmatch(text); len(m) >= 2 && m[1] != "" {
			s := strings.ReplaceAll(m[1], ",", "")
			if n, err := strconv.ParseFloat(s, 64); err == nil && n > 0 {
				return n, true
			}
		}
	}
	return 0, false
}

// ---------- direction ----------

var (
	reDebit  = regexp.MustCompile(`(?i)\b(debited?|charged|spent|paid\s+to|paid\s+via|sent\s+to|transfer(?:red)?\s+to|withdrawn|used\s+(?:for|at)|purchase[d]?)\b`)
	reCredit = regexp.MustCompile(`(?i)\b(credited?|received|refund(?:ed)?|cashback|reversed?|reversal)\b`)
)

// ---------- merchant ----------
// Patterns ordered by specificity; first match wins.
// Lookaheads from the TS source are replaced with "lazy group + required terminator":
//   - merchant is captured with `{1,38}?` (lazy/reluctant)
//   - the lookahead content becomes a non-capturing `(?:...)` that must match
// Group [1] is always the merchant name.
var merchantREs = []*regexp.Regexp{
	// UPI VPA: "to merchant@upi" / "VPA merchant@bank" — handle before @
	regexp.MustCompile(`(?i)\b(?:to|vpa)[:\s]+([A-Za-z0-9][-A-Za-z0-9._]{1,38}?)@[a-z]+`),
	// "paid to / sent to / trf to / transferred to MERCHANT <terminator>"
	regexp.MustCompile(`(?i)\b(?:paid\s+to|sent\s+to|trf\s+to|transfer(?:red)?\s+to)\s+([A-Za-z0-9][-A-Za-z0-9 @&.']{1,38}?)(?:\s+(?:on\s+\d|via|refno?|upi|for|amt|amount|on\b)|[,.]|$)`),
	// "towards MERCHANT <terminator>" (ICICI style)
	regexp.MustCompile(`(?i)\btowards\s+([A-Za-z0-9][-A-Za-z0-9 &.']{1,38}?)(?:\s+(?:on\s+\d|via|ref|upi)|[,.]|$)`),
	// "at MERCHANT <terminator>" (card transaction style)
	regexp.MustCompile(`(?i)\bat\s+([A-Za-z0-9*][-A-Za-z0-9 *&.']{1,38}?)(?:\s+(?:on\s+\d|for|via|amounting)|[,.]|$)`),
	// "Info: MERCHANT <terminator>"
	regexp.MustCompile(`(?i)\binfo[:\-]\s*([A-Za-z0-9][-A-Za-z0-9 *&.'/]{1,38}?)(?:\s+(?:on\s+\d|ref)|[,.]|$)`),
	// "transaction at/to MERCHANT <terminator>"
	regexp.MustCompile(`(?i)\btransaction\s+(?:at|to)\s+([A-Za-z0-9][-A-Za-z0-9 &.']{1,38}?)(?:\s+(?:for|on\s+\d|via)|[,.]|$)`),
	// "; MERCHANT credited" (ICICI: payee after amount before "credited")
	regexp.MustCompile(`;\s*([A-Z][-A-Za-z0-9 &.']{1,38}?)\s+credited\b`),
	// "to MERCHANT <terminator>" generic last resort
	regexp.MustCompile(`(?i)\bto\s+([A-Za-z0-9][-A-Za-z0-9 &.']{1,38}?)(?:\s+(?:on\s+\d|via|ref|upi|for|amt)|[,.]|$)`),
}

func parseMerchant(text string) *string {
	for _, re := range merchantREs {
		if m := re.FindStringSubmatch(text); len(m) >= 2 && m[1] != "" {
			s := strings.Join(strings.Fields(strings.TrimSpace(m[1])), " ")
			if s != "" {
				return &s
			}
		}
	}
	return nil
}

// ---------- date ----------

var (
	reNumericDate = regexp.MustCompile(`\b(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})\b`)
	reMonthDate   = regexp.MustCompile(`(?i)\b(\d{1,2})[-\s]?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[-\s,]?\s*(\d{2,4})\b`)
)

var monthMap = map[string]string{
	"jan": "01", "feb": "02", "mar": "03", "apr": "04", "may": "05", "jun": "06",
	"jul": "07", "aug": "08", "sep": "09", "oct": "10", "nov": "11", "dec": "12",
}

func parseDate(text string) *string {
	if m := reNumericDate.FindStringSubmatch(text); m != nil {
		d, mo, y := m[1], m[2], m[3]
		if len(y) == 2 {
			y = "20" + y
		}
		s := y + "-" + pad2(mo) + "-" + pad2(d)
		return &s
	}
	if m := reMonthDate.FindStringSubmatch(text); m != nil {
		d, mon, y := m[1], m[2], m[3]
		if len(y) == 2 {
			y = "20" + y
		}
		mo := monthMap[strings.ToLower(mon[:3])]
		s := y + "-" + mo + "-" + pad2(d)
		return &s
	}
	return nil
}

func pad2(s string) string {
	if len(s) < 2 {
		return "0" + s
	}
	return s
}

// ---------- reference ID ----------

var reRef = regexp.MustCompile(`(?i)(?:UPI\s*Ref\.?\s*(?:No\.?)?\s*[:#]?\s*|UPI\s*[:#]\s*|Ref\.?\s*(?:No\.?)?\s*[:#]?\s*|Trans(?:action)?\s*ID\s*[:#]?\s*)([A-Za-z0-9]{8,30})`)

// ---------- payment method ----------

var (
	reUPI        = regexp.MustCompile(`(?i)\bUPI\b`)
	reCreditCard = regexp.MustCompile(`(?i)credit\s*card`)
	reDebitCard  = regexp.MustCompile(`(?i)debit\s*card`)
	reNetBanking = regexp.MustCompile(`(?i)net\s*banking`)
	reWallet     = regexp.MustCompile(`(?i)wallet`)
)

func detectPaymentMethod(text string) *string {
	var s string
	switch {
	case reUPI.MatchString(text):
		s = "UPI"
	case reCreditCard.MatchString(text):
		s = "Credit Card"
	case reDebitCard.MatchString(text):
		s = "Debit Card"
	case reNetBanking.MatchString(text):
		s = "Net Banking"
	case reWallet.MatchString(text):
		s = "Wallet"
	default:
		return nil
	}
	return &s
}

// ---------- main entry point ----------

// Input carries the fields available from an SMS or email message.
type Input struct {
	From    string
	Subject string
	Body    string
}

// ParseText extracts a ParsedTransaction from an SMS or email message.
// Returns model.Empty() (IsTransaction=false) when the text is not a financial transaction.
// Mirrors parseText() from parser.ts with identical logic.
func ParseText(in Input) model.ParsedTransaction {
	empty := model.Empty()

	// Scan up to 4000 chars. HTML bank emails bury the real alert line well past
	// the first 1000 chars; the old cap was a primary reason emails parsed to nothing.
	full := in.Subject + "\n" + in.Body
	if len(full) > 4000 {
		full = full[:4000]
	}

	// Gate: MightBeFinancial is the looser check (no currency-symbol requirement) so we
	// still reach amount extraction for symbol-less formats like SBI UPI "debited by 500.0".
	if !MightBeFinancial(in.Subject, in.Body) {
		return empty
	}

	amount, ok := parseAmount(full)
	if !ok {
		return empty
	}

	// Prefer explicit credit over debit; debit is the default.
	isCredit := reCredit.MatchString(full)
	isDebit := reDebit.MatchString(full)
	dir := model.Debit
	if isCredit && !isDebit {
		dir = model.Credit
	}

	merchant := parseMerchant(full)
	date := parseDate(full)

	var refID *string
	if m := reRef.FindStringSubmatch(full); len(m) >= 2 {
		s := m[1]
		refID = &s
	}

	pm := detectPaymentMethod(full)

	return model.ParsedTransaction{
		IsTransaction: true,
		Amount:        &amount,
		Currency:      "INR",
		Direction:     dir,
		Merchant:      merchant,
		PaymentMethod: pm,
		Date:          date,
		ReferenceID:   refID,
		Category:      "Uncategorized",
	}
}
