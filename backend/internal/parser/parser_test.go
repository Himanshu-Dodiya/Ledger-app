package parser

import (
	"testing"

	"github.com/himanshu/ledger-api/internal/model"
)

// ptr helpers
func pf(v float64) *float64 { return &v }
func ps(v string) *string   { return &v }

type parseCase struct {
	name      string
	in        Input
	wantTxn   bool
	wantAmt   *float64
	wantDir   model.Direction
	wantMerch *string // nil means "don't check"
	wantDate  *string // nil means "don't check"
	wantRef   *string // nil means "don't check"
	wantPM    *string // nil means "don't check"
}

func TestParseText(t *testing.T) {
	cases := []parseCase{
		{
			name:    "HDFC UPI debit",
			in:      Input{Body: "Rs.500.00 debited from a/c **1234 on 01-06-25. UPI:Swiggy@icicici. Avl bal:12000.00. Call 18002586161 for dispute."},
			wantTxn: true, wantAmt: pf(500), wantDir: model.Debit,
			wantDate: ps("2025-06-01"), wantPM: ps("UPI"),
		},
		{
			name:    "ICICI credit card debit",
			in:      Input{Body: "ICICI Bank Credit Card XX1234 used for Rs 1,234.50 at AMAZON on 02-Jun-25. If not done, call 18002662."},
			wantTxn: true, wantAmt: pf(1234.50), wantDir: model.Debit,
			wantMerch: ps("AMAZON"), wantDate: ps("2025-06-02"), wantPM: ps("Credit Card"),
		},
		{
			// Real SBI UPI format: no currency symbol anywhere — only verb-based amount pattern works.
			name:    "SBI UPI no-symbol debit (symbol-less format)",
			in:      Input{Body: "Dear UPI user A/C X1234 debited by 750.0 on date 03Jun25 trf to Zomato Refno 412345678901. If not u call 18001111."},
			wantTxn: true, wantAmt: pf(750), wantDir: model.Debit,
			wantDate: ps("2025-06-03"),
		},
		{
			name:    "UPI credit / refund",
			in:      Input{Body: "₹200.00 credited to your a/c XX5678 by Paytm Cashback on 05-06-2025. UPI Ref No 123456789012."},
			wantTxn: true, wantAmt: pf(200), wantDir: model.Credit,
			wantDate: ps("2025-06-05"), wantRef: ps("123456789012"),
		},
		{
			name:    "Amount suffix currency",
			in:      Input{Body: "Transaction of 999 INR at Netflix subscription on 07/06/2025."},
			wantTxn: true, wantAmt: pf(999), wantDir: model.Debit,
			wantDate: ps("2025-06-07"),
		},
		{
			name:    "OTP — must be rejected",
			in:      Input{Body: "123456 is your OTP for login. Do not share with anyone. Valid for 10 min."},
			wantTxn: false,
		},
		{
			name:    "Statement ready — must be rejected",
			in:      Input{Body: "Your HDFC Bank Credit Card statement is ready. Log in to NetBanking."},
			wantTxn: false,
		},
		{
			name:    "ICICI 'paid to' merchant",
			in:      Input{Body: "INR 300.00 paid to Dominos Pizza via UPI on 08-06-2025. Ref No 987654321098."},
			wantTxn: true, wantAmt: pf(300), wantDir: model.Debit,
			wantMerch: ps("Dominos Pizza"), wantPM: ps("UPI"),
		},
		{
			name:    "Amount label format",
			in:      Input{Subject: "Payment Successful", Body: "Amount: Rs 4,500 paid to Jio Fiber. Transaction ID TXN20250601."},
			wantTxn: true, wantAmt: pf(4500), wantDir: model.Debit,
			wantRef: ps("TXN20250601"),
		},
		{
			name:    "Completely non-financial text",
			in:      Input{Body: "Your package has been shipped. Expected delivery: Monday."},
			wantTxn: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ParseText(tc.in)
			if got.IsTransaction != tc.wantTxn {
				t.Fatalf("IsTransaction: got %v, want %v", got.IsTransaction, tc.wantTxn)
			}
			if !tc.wantTxn {
				return
			}
			if tc.wantAmt != nil {
				if got.Amount == nil {
					t.Fatalf("Amount: got nil, want %.2f", *tc.wantAmt)
				}
				if *got.Amount != *tc.wantAmt {
					t.Errorf("Amount: got %.2f, want %.2f", *got.Amount, *tc.wantAmt)
				}
			}
			if got.Direction != tc.wantDir {
				t.Errorf("Direction: got %q, want %q", got.Direction, tc.wantDir)
			}
			if tc.wantMerch != nil {
				if got.Merchant == nil {
					t.Errorf("Merchant: got nil, want %q", *tc.wantMerch)
				} else if *got.Merchant != *tc.wantMerch {
					t.Errorf("Merchant: got %q, want %q", *got.Merchant, *tc.wantMerch)
				}
			}
			if tc.wantDate != nil {
				if got.Date == nil {
					t.Errorf("Date: got nil, want %q", *tc.wantDate)
				} else if *got.Date != *tc.wantDate {
					t.Errorf("Date: got %q, want %q", *got.Date, *tc.wantDate)
				}
			}
			if tc.wantRef != nil {
				if got.ReferenceID == nil {
					t.Errorf("ReferenceID: got nil, want %q", *tc.wantRef)
				} else if *got.ReferenceID != *tc.wantRef {
					t.Errorf("ReferenceID: got %q, want %q", *got.ReferenceID, *tc.wantRef)
				}
			}
			if tc.wantPM != nil {
				if got.PaymentMethod == nil {
					t.Errorf("PaymentMethod: got nil, want %q", *tc.wantPM)
				} else if *got.PaymentMethod != *tc.wantPM {
					t.Errorf("PaymentMethod: got %q, want %q", *got.PaymentMethod, *tc.wantPM)
				}
			}
		})
	}
}

func TestMightBeFinancial(t *testing.T) {
	cases := []struct {
		subj, body string
		want       bool
	}{
		{"", "Rs 500 debited from your account", true},
		{"", "Your OTP is 123456. Do not share.", false},
		{"", "Your package has been shipped.", false},
		{"Payment alert", "INR 100 paid via UPI", true},
	}
	for _, tc := range cases {
		got := MightBeFinancial(tc.subj, tc.body)
		if got != tc.want {
			t.Errorf("MightBeFinancial(%q, %q) = %v, want %v", tc.subj, tc.body, got, tc.want)
		}
	}
}

func TestParseDate(t *testing.T) {
	cases := []struct{ in, want string }{
		{"debited on 01-06-2025", "2025-06-01"},
		{"on 02/06/25", "2025-06-02"},
		{"on 03Jun25", "2025-06-03"},
		{"on 4 June 2025", "2025-06-04"},
		{"on 05-Jun-2025", "2025-06-05"},
	}
	for _, tc := range cases {
		got := parseDate(tc.in)
		if got == nil {
			t.Errorf("parseDate(%q) = nil, want %q", tc.in, tc.want)
		} else if *got != tc.want {
			t.Errorf("parseDate(%q) = %q, want %q", tc.in, *got, tc.want)
		}
	}
}
