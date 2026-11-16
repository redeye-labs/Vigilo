# Changelog

## 1.0.1
- Corretto: "Supervisor non disponibile" anche dentro Home Assistant. L'immagine base usa s6-overlay,
  che azzera l'ambiente del processo e nascondeva il token del Supervisor: ora il token viene letto anche
  da /run/s6/container_environment e l'ambiente viene mantenuto (S6_KEEP_ENV=1). Di conseguenza tornano
  a funzionare il rilevamento automatico di Mosquitto e l'elenco dei telefoni (notify.mobile_app_*).
- Nuove opzioni facoltative mqtt_host, mqtt_port, mqtt_username, mqtt_password, mqtt_ssl per indicare
  il broker MQTT a mano se il rilevamento automatico non funziona.
- La procedura guidata mostra il motivo se non riesce a leggere i telefoni; i telefoni compaiono per primi.
- Il registro indica all'avvio se il token del Supervisor è stato trovato (senza mostrarlo).

## 1.0.0
- Prima versione: nodo Tailscale integrato con Funnel automatico, login Tesla, chiave virtuale,
  proxy comandi ufficiale, firma e invio della configurazione Fleet Telemetry, server fleet-telemetry,
  entità MQTT, notifiche, API e QR code per l'app Android, controllo giornaliero.
