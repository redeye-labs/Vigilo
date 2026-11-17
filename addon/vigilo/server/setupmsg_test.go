package main

import "testing"

func TestStaleSetupMsgCleared(t *testing.T) {
	a := &App{}
	a.setupMsg("configurazione non applicata: " + describeSkip("missing_key"))
	partial := []Check{{ID: "key", OK: true}, {ID: "config", OK: true}, {ID: "car", OK: false}}
	if m := a.currentSetupMsg(partial); m == "" {
		t.Fatal("con controlli non tutti verdi il messaggio deve restare")
	}
	all := []Check{{ID: "key", OK: true}, {ID: "config", OK: true}, {ID: "car", OK: true}}
	if m := a.currentSetupMsg(all); m != "" {
		t.Fatalf("con chiave, configurazione e dati a posto il messaggio va nascosto, ho %q", m)
	}
	if m := a.currentSetupMsg(partial); m != "" {
		t.Fatalf("il messaggio superato deve restare cancellato, ho %q", m)
	}
	a.setupMsg("configurazione non applicata: " + describeSkip("missing_key"))
	a.clearSetupMsgIf(func(m string) bool { return false })
	if a.lastSetupMsg == "" {
		t.Fatal("clearSetupMsgIf non deve cancellare se la condizione è falsa")
	}
}
