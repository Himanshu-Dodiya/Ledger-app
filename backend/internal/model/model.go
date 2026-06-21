// Package model holds the shared domain types used across parsing, categorization,
// LLM fallback, and persistence. Kept dependency-free to avoid import cycles.
package model

// Direction of money movement.
type Direction string

const (
	Debit  Direction = "debit"
	Credit Direction = "credit"
)

// Source of a transaction row. Every import path ultimately produces a transaction
// with one of these source labels; the unified model is otherwise identical.
const (
	SourceSMS        = "sms"
	SourceGmail      = "gmail"
	SourceManual     = "manual"
	SourceGPayPDF    = "gpay_pdf"
	SourcePaytmPDF   = "paytm_pdf"
	SourcePhonePePDF = "phonepe_pdf"
	SourceBankPDF    = "bank_pdf"
	SourceCSV        = "csv"
	SourceQR         = "qr"
)

// validSources is the set accepted by the batch-ingest endpoint.
var validSources = map[string]bool{
	SourceSMS: true, SourceGmail: true, SourceManual: true,
	SourceGPayPDF: true, SourcePaytmPDF: true, SourcePhonePePDF: true,
	SourceBankPDF: true, SourceCSV: true, SourceQR: true,
}

// IsValidSource reports whether s is a known source label.
func IsValidSource(s string) bool { return validSources[s] }

// Categories is the fixed category set, shared with the Android app and the web
// dashboard. Order is intentional (Uncategorized last).
var Categories = []string{
	"Food & Dining",
	"Groceries",
	"Shopping",
	"Transport",
	"Bills & Utilities",
	"Subscriptions",
	"Entertainment",
	"Health",
	"Transfers",
	"Income",
	"Uncategorized",
}

// IsValidCategory reports whether c is one of the allowed categories.
func IsValidCategory(c string) bool {
	for _, x := range Categories {
		if x == c {
			return true
		}
	}
	return false
}

// ParsedTransaction is the shape produced by the regex parser and the LLM fallback.
// Mirrors the web app's ParsedTransaction (src/lib/types.ts).
type ParsedTransaction struct {
	IsTransaction bool      `json:"isTransaction"`
	Amount        *float64  `json:"amount"`
	Currency      string    `json:"currency"`
	Direction     Direction `json:"direction"`
	Merchant      *string   `json:"merchant"`
	PaymentMethod *string   `json:"paymentMethod"`
	Date          *string   `json:"date"` // ISO yyyy-mm-dd
	ReferenceID   *string   `json:"referenceId"`
	Category      string    `json:"category"`
}

// Empty returns a non-transaction ParsedTransaction with sane defaults.
func Empty() ParsedTransaction {
	return ParsedTransaction{
		IsTransaction: false,
		Amount:        nil,
		Currency:      "INR",
		Direction:     Debit,
		Merchant:      nil,
		PaymentMethod: nil,
		Date:          nil,
		ReferenceID:   nil,
		Category:      "Uncategorized",
	}
}
