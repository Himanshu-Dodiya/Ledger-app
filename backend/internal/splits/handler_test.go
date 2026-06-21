package splits

import (
	"math"
	"testing"
)

func f(v float64) *float64 { return &v }

// sumExact asserts the resolved shares add up to total to the paisa, with no float drift.
func sumExact(t *testing.T, got []float64, total float64) {
	t.Helper()
	var sumP int64
	for _, g := range got {
		sumP += int64(math.Round(g * 100))
	}
	if sumP != int64(math.Round(total*100)) {
		t.Fatalf("shares sum to %d paise, want %d (%v)", sumP, int64(math.Round(total*100)), got)
	}
}

func TestResolveEqual_100by3(t *testing.T) {
	ps := []splitParticipant{{}, {}, {}}
	got, err := resolveShares("equal", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	want := []float64{33.33, 33.33, 33.34}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("idx %d = %.2f, want %.2f (%v)", i, got[i], want[i], got)
		}
	}
	sumExact(t, got, 100)
}

func TestResolveEqual_100by6(t *testing.T) {
	ps := make([]splitParticipant, 6)
	got, err := resolveShares("equal", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	want := []float64{16.66, 16.66, 16.67, 16.67, 16.67, 16.67}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("idx %d = %.2f, want %.2f (%v)", i, got[i], want[i], got)
		}
	}
	sumExact(t, got, 100)
}

func TestResolveEqual_AlwaysSums(t *testing.T) {
	for _, total := range []float64{0.01, 1, 9.99, 100, 1000.05, 33.33, 7} {
		for n := 1; n <= 9; n++ {
			ps := make([]splitParticipant, n)
			got, err := resolveShares("equal", total, ps)
			if err != nil {
				t.Fatalf("total %.2f n %d: %v", total, n, err)
			}
			sumExact(t, got, total)
		}
	}
}

func TestResolvePercent(t *testing.T) {
	ps := []splitParticipant{{Value: f(50)}, {Value: f(30)}, {Value: f(20)}}
	got, err := resolveShares("percent", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	if got[0] != 50 || got[1] != 30 || got[2] != 20 {
		t.Errorf("got %v", got)
	}
	sumExact(t, got, 100)
}

func TestResolvePercent_Uneven(t *testing.T) {
	// Thirds via percent: largest-remainder should keep the sum exact.
	ps := []splitParticipant{{Value: f(33.33)}, {Value: f(33.33)}, {Value: f(33.34)}}
	got, err := resolveShares("percent", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	sumExact(t, got, 100)
}

func TestResolveShares_Weighted(t *testing.T) {
	ps := []splitParticipant{{Value: f(1)}, {Value: f(1)}, {Value: f(2)}}
	got, err := resolveShares("shares", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	// 1:1:2 of 100 → 25,25,50
	if got[2] != 50 {
		t.Errorf("got %v, want last 50", got)
	}
	sumExact(t, got, 100)
}

func TestResolveExact_Valid(t *testing.T) {
	ps := []splitParticipant{{Value: f(25.50)}, {Value: f(74.50)}}
	got, err := resolveShares("exact", 100, ps)
	if err != nil {
		t.Fatal(err)
	}
	sumExact(t, got, 100)
}

func TestResolveExact_MismatchRejected(t *testing.T) {
	ps := []splitParticipant{{Value: f(25)}, {Value: f(50)}}
	if _, err := resolveShares("exact", 100, ps); err == nil {
		t.Fatal("expected error when exact amounts don't sum to total")
	}
}

func TestResolve_NoParticipants(t *testing.T) {
	if _, err := resolveShares("equal", 100, nil); err == nil {
		t.Fatal("expected error for zero participants")
	}
}
