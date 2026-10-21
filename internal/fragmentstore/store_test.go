package fragmentstore

import (
	"testing"
	"time"
)

func TestCollectSingleFragmentMarksCompletedWithinRetention(t *testing.T) {
	store := New[string](4)
	now := time.Unix(1700000000, 0)

	assembled, ready, completed := store.Collect("req", []byte("hello"), 0, 1, now, 5*time.Minute)
	if !ready || completed {
		t.Fatalf("expected first single-fragment collect to complete once, ready=%v completed=%v", ready, completed)
	}
	if string(assembled) != "hello" {
		t.Fatalf("unexpected assembled payload: %q", string(assembled))
	}

	assembled, ready, completed = store.Collect("req", []byte("hello"), 0, 1, now.Add(time.Second), 5*time.Minute)
	if ready || !completed || assembled != nil {
		t.Fatalf("expected duplicate single-fragment collect to be completed-only, ready=%v completed=%v payload=%v", ready, completed, assembled)
	}
}

func TestRemoveIfClearsItemsAndCompletedEntries(t *testing.T) {
	store := New[int](4)
	now := time.Unix(1700000000, 0)

	if _, ready, _ := store.Collect(1, []byte("a"), 0, 2, now, 5*time.Minute); ready {
		t.Fatal("expected first fragment to stay incomplete")
	}
	if _, ready, completed := store.Collect(2, []byte("b"), 0, 1, now, 5*time.Minute); !ready || completed {
		t.Fatal("expected single fragment key to complete")
	}

	store.RemoveIf(func(key int) bool { return key == 1 || key == 2 })

	if _, ready, completed := store.Collect(1, []byte("c"), 1, 2, now.Add(time.Second), 5*time.Minute); ready || completed {
		t.Fatalf("expected removed incomplete key to behave as empty state, ready=%v completed=%v", ready, completed)
	}
	if payload, ready, completed := store.Collect(2, []byte("d"), 0, 1, now.Add(time.Second), 5*time.Minute); !ready || completed || string(payload) != "d" {
		t.Fatalf("expected removed completed key to accept new data, ready=%v completed=%v payload=%q", ready, completed, string(payload))
	}
}

// TestStoreBoundsIncompleteAssemblies verifies the hard cap on live reassembly
// entries. Keys come from packet headers, so without a cap a peer can pin one
// entry (with its 256-slot chunk table) per invented sequence number.
func TestStoreBoundsIncompleteAssemblies(t *testing.T) {
	const capacity = 2
	store := New[int](capacity)
	now := time.Unix(1700000000, 0)

	for i := 0; i < 256; i++ {
		if _, ready, _ := store.Collect(i, []byte("frag"), 0, 4, now, 5*time.Minute); ready {
			t.Fatalf("single fragment of a four-fragment message reported ready")
		}
		if got := len(store.items); got > capacity {
			t.Fatalf("store holds %d entries, cap is %d", got, capacity)
		}
	}

	// A fresh key must still be able to start and complete an assembly after
	// eviction pressure: the cap must not wedge the store.
	if _, ready, _ := store.Collect(9999, []byte("a"), 0, 2, now, 5*time.Minute); ready {
		t.Fatal("first fragment must not complete")
	}
	assembled, ready, _ := store.Collect(9999, []byte("b"), 1, 2, now, 5*time.Minute)
	if !ready || string(assembled) != "ab" {
		t.Fatalf("expected a fresh assembly to complete after eviction, ready=%v payload=%q", ready, assembled)
	}
}

// TestStoreBoundsCompletedMarkers verifies the recent-completion cache is also
// bounded; it is keyed by the same attacker-controlled sequence numbers.
func TestStoreBoundsCompletedMarkers(t *testing.T) {
	const capacity = 3
	store := New[int](capacity)
	now := time.Unix(1700000000, 0)

	for i := 0; i < 512; i++ {
		if _, ready, completed := store.Collect(i, []byte("x"), 0, 1, now, 5*time.Minute); !ready || completed {
			t.Fatalf("single-fragment payload did not complete cleanly")
		}
		if got := len(store.completed); got > capacity {
			t.Fatalf("completed map holds %d markers, cap is %d", got, capacity)
		}
	}
}
