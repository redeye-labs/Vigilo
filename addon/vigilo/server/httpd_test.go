package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequirePairingMarksApp(t *testing.T) {
	st, err := NewStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if st.Get().PairedApps != 0 {
		t.Fatalf("paired_apps iniziale: %d", st.Get().PairedApps)
	}
	token := st.Get().PairingToken
	a := &App{store: st}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ok", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	h := a.requirePairing(mux)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/ok", nil))
	if rec.Code != 401 {
		t.Fatalf("senza token: %d", rec.Code)
	}
	if st.Get().PairedApps != 0 {
		t.Fatal("un 401 non deve contare come app collegata")
	}

	req := httptest.NewRequest("GET", "/ok", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("con token: %d", rec.Code)
	}
	if st.Get().PairedApps != 1 {
		t.Fatalf("dopo il primo accesso: %d", st.Get().PairedApps)
	}

	req = httptest.NewRequest("GET", "/ok", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if st.Get().PairedApps != 1 {
		t.Fatalf("il secondo accesso non deve incrementare: %d", st.Get().PairedApps)
	}

	st.ResetPairing()
	if st.Get().PairedApps != 0 || st.Get().PairingToken == token {
		t.Fatal("ResetPairing deve azzerare le app e cambiare il token")
	}
}
