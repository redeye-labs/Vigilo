package main

import (
 "encoding/json"
 "fmt"
 "net/http"
 "strings"
 "time"
)

// Isolated Home Assistant smoke mode. It deliberately initializes none of:
// Store, Tesla, MQTT, Tailscale, Fleet Telemetry, scheduler, credentials or
// legacy files. Only the Home Assistant ingress UI can access this handler.
// This is the first *deployment* gate, not the real-vehicle integration test.
func isolatedPreviewHandler() http.Handler {
 mux:=http.NewServeMux()
 mux.HandleFunc("/",func(w http.ResponseWriter,r *http.Request){
  if r.URL.Path!="/" {
   http.NotFound(w,r)
   return
  }
  if r.Method!=http.MethodGet && r.Method!=http.MethodHead{
   w.Header().Set("Allow","GET, HEAD")
   http.Error(w,"method not allowed",http.StatusMethodNotAllowed)
   return
  }
  w.Header().Set("Content-Type","text/html; charset=utf-8")
  w.Header().Set("Cache-Control","no-store")
  w.Header().Set("X-Content-Type-Options","nosniff")
  w.Header().Set("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
  fmt.Fprint(w,`<!doctype html><html lang="it"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vigilo 2 - Collaudo isolato</title>
<style>body{font:16px system-ui,sans-serif;max-width:650px;margin:4rem auto;padding:0 1rem;background:#111b21;color:#eef6f7}
article{border:1px solid #56717a;border-radius:14px;padding:24px;background:#1c2b31}h1{font-size:1.65rem}
small{color:#aec7ca}.ok{color:#8ee2bd;font-weight:700}</style>
<article><h1>Vigilo 2 · Collaudo isolato</h1><p class="ok">Server di prova avviato correttamente.</p>
<p>Questa installazione serve solo a verificare che Home Assistant possa avviare il nuovo backend.
Non accede alla Tesla, non usa Tailscale, non invia comandi e non modifica lo storico Vigilo 1.x.</p>
<p><small>Prossimo passaggio: test di backup/ripristino e integrazione Tesla in un ambiente separato, con autorizzazione dedicata.</small></p></article></html>`)
 })
 mux.HandleFunc("/health",func(w http.ResponseWriter,r *http.Request){
  if r.Method!=http.MethodGet && r.Method!=http.MethodHead{
   w.Header().Set("Allow","GET, HEAD")
   http.Error(w,"method not allowed",http.StatusMethodNotAllowed)
   return
  }
  w.Header().Set("Content-Type","application/json")
  w.Header().Set("Cache-Control","no-store")
  w.Header().Set("X-Content-Type-Options","nosniff")
  _=json.NewEncoder(w).Encode(map[string]any{
   "ok":true,"mode":"isolated_preview","sentry_connected":false,
   "tesla_commands_enabled":false,"fleet_telemetry_enabled":false,
   "mqtt_enabled":false,"tailscale_enabled":false,
   "smoke_test_only":true,
  })
 })
 return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  if strings.HasPrefix(r.URL.Path,"/api/") {
   http.NotFound(w,r)
   return
  }
  mux.ServeHTTP(w,r)
 })
}

// health probe for a local add-on; does not touch persistent data.
func serveIsolatedPreview(addr string) error {
 server:=&http.Server{
  Addr:addr,
  Handler:isolatedPreviewHandler(),
  ReadHeaderTimeout:15*time.Second,
 }
 return server.ListenAndServe()
}
