# Vigilo 2 · Stato di completamento

Base verificata su GitHub: `7eb9d4c578947166b0fcd065fdbd5ed929da162f`, APK 2.0.0-beta.5 build 36, add-on 2.0.0-beta.5. Punto di ripristino dei sorgenti: `vigilo-2-completion-base-7eb9d4c`. Il backup dei dati privati è separato.

## Changelog del ramo

- Accesso a Parcheggi, Posizione attuale e Impostazioni dalla lista Home.
- Parcheggi mostra intervalli osservati, energia da fermo e distinzione fra Sentinella attiva, spenta o incerta. Il contatore rappresenta la scarica del veicolo durante la sosta. Una lacuna interrompe la cronologia.
- Il modulo Viaggi richiede campioni periodici durante lo streaming attivo per correlare energia, odometro e stato. Tesla documenta `resend_interval_seconds` da firmware 2024.44.32. La raccolta resta opt-in e richiede una verifica di compatibilità e volume sul veicolo.
- La rotta legacy dei comandi v2 apre la schermata Comandi unica.
- Diagnostica mostra le temperature interne ed esterne già raccolte e usa una vista dall'alto originale per le quattro pressioni.
- Aggiunte regressioni per il caso di autonomia in miglia e per gli intervalli di sosta incompleti.

## Inventario e lavoro residuo

| Area | Presente | Da completare e collaudare |
| --- | --- | --- |
| Batteria | SOC, autonomia nominale, stima dai consumi, capacità e temperature BMS, energia residua, cicli stimati, indicazione freschezza | Storico BMS completo e segmentazione al cambio pacco. SoH assoluto richiede una misura iniziale confrontabile e documentata |
| Ricariche | Contatore batteria, sessioni osservate, storico, tariffa manuale e grafico | Potenza, durata verificabile e aggregazioni per tutti i periodi. L'energia in batteria è distinta dalla misura del caricatore e dagli importi fatturati |
| Viaggi | Odometro e consumo su intervalli osservati, grafico delle distanze | Associazione del consumo a ciascuna percorrenza, durata, confronto, grafici e costi. Il contatore di scarica include i carichi del veicolo e non misura il consumo netto dopo rigenerazione |
| Parcheggi | Schermata dedicata, grafico e intervalli osservati, consumo orario e distinzione Sentinella | SOC comparabile ai confini, costi e distinzione affidabile delle soste in ricarica. I periodi senza campioni rimangono scoperti |
| Salute veicolo | Connessione, stato telemetria, pneumatici e temperature | Cronologia firmware, anomalie documentate e collaudo visuale |
| Comandi | Schermata con azioni esistenti e pannello comandi v2 integrato | Raggruppamento completo e collaudo guardie |
| Automazioni | Programma Sentinella e regole server opt-in per SOC, Panic e parcheggio fuori casa | Pianificazioni e condizioni aggiuntive, storico esecuzioni, eliminazione duplicati e verifica stato |
| Dati e rilascio | Widget, notifiche, report, backup, esportazione, flussi firma e API esistenti | Valutazione completa delle migrazioni, test Android, screenshot eseguiti, test Docker/HA, verifica aggiornamento e ripristino |

## Fonti e semantica

- [Tesla Fleet Telemetry Available Data](https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data) collega `RatedRange` a `charge_state.battery_range`, specifica che l'odometro è in miglia e distingue `LifetimeEnergyUsed` dal contatore di guida disponibile solo per Semi. `DCChargingEnergyIn` misura l'energia ricevuta in batteria sia in AC sia in DC. `ACChargingEnergyIn` è la misura del caricatore per sessioni AC.
- [Tesla Fleet Telemetry](https://developer.tesla.com/docs/fleet-api/fleet-telemetry) descrive lo streaming basato sulle variazioni. Gli [annunci Fleet API](https://developer.tesla.com/docs/fleet-api/announcements) documentano l'opzione di reinvio periodico.
- I riferimenti funzionali Tessie sono [salute batteria](https://help.tessie.com/article/39-battery-health), [ricariche](https://help.tessie.com/article/143-charge-tracking), [viaggi](https://help.tessie.com/article/43-drive-tracking), [soste](https://help.tessie.com/article/144-idle-tracking) e [automazioni](https://help.tessie.com/article/46-automation). Vigilo limita queste analisi ai dati del server personale e agli intervalli osservati.

## Installazione e ripristino

Istruzioni di installazione per [Home Assistant e Docker](../README.md). Prima di un aggiornamento, conservare una copia verificata del volume privato e dell'APK firmato installato. Verificare application ID, certificato e incremento del versionCode del candidato. Provare aggiornamento e accesso ai dati prima in un ambiente di collaudo. Il ripristino server usa la versione originaria e il relativo backup del volume secondo la [procedura di backup e ripristino](VIGILO_2_BACKUP_AND_RECOVERY.md). Android generalmente rifiuta l'installazione di un APK con versionCode inferiore. Per un rollback in-place serve ricompilare la revisione precedente con un versionCode superiore e la chiave originaria, verificando la compatibilità con i dati già migrati.
