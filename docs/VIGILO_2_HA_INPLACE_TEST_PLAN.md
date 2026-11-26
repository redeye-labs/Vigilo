# Vigilo 2 — preparazione dell'aggiornamento **in-place** su Home Assistant

**Stato:** candidato sorgente beta, non installato. Procedura di recupero obbligatoria.

L'utente ha scelto di usare la propria istanza HA e la Tesla reale, non il
pacchetto di smoke test. Il pacchetto `Vigilo2-HA-InPlace-Candidate` conserva
`slug: vigilo` e la configurazione Supervisor originale; NON installa né
aggiorna automaticamente l'add-on. Non sostituire l'installazione con il pacchetto
`vigilo2_smoke`, che ha identificativo differente e Tesla disabilitata.

## Prima di iniziare — controllo bloccante

1. Da Home Assistant vai in **Impostazioni → Sistema → Backup**.
2. Crea un backup completo e assicurati che contenga **Vigilo e i suoi dati**,
   non solo le impostazioni dell'interfaccia.
3. Scarica una copia fuori dal dispositivo Home Assistant e verifica che il file
   si apra correttamente. Conserva la chiave di cifratura/emergency kit
   quando necessaria. I file scaricati dalla pagina Backup possono essere
   decifrati durante il download: trattali come segreti.
4. Verifica il nome, lo slug e l'origine dell'add-on installato. Un add-on
   proveniente da un repository personalizzato non è intercambiabile
   automaticamente con una copia sotto `/addons` avente lo stesso slug.
5. Annotati la versione in uso, la posizione del repository di installazione e
   l'orario dell'ultima telemetria. Salva separatamente gli screenshot di
   connessione Tailscale, broker MQTT e stato Sentinella, oscurando URL e token.
6. Definisci concretamente come ripristinare **la stessa istanza add-on e /data**
   dal backup. I branch di GitHub non recuperano token Tesla o chiavi private.

**Non avviare l'aggiornamento senza aver soddisfatto tutto il controllo.**

## Cosa viene preparato automaticamente

La pipeline GitHub Actions del branch `feature/vigilo-2-foundations`:
- esegue test Go, vet, build Android e test unitari;
- soltanto dopo esito positivo crea l'archivio sorgente
  `vigilo2-ha-upgrade-candidate.tar.gz` e il suo SHA-256;
- verifica slug `vigilo`, versione `2.0.0-beta.1`, Dockerfile,
  indirizzo Ingress e presenza del test di compatibilità telemetria;
- esclude da quell'archivio credenziali e archivi dati;
- **non modifica `main`, non pubblica una release e non esegue comandi sul server**.

Lo ZIP dell'artifact GitHub contiene il TAR sorgente, non un installer eseguibile.
Il repository di produzione oggi usa la versione stabile in `main`.
Per aggiornare la stessa istanza, occorre un passaggio di distribuzione
compatibile con l'origine dell'add-on (repository/slug confermati dall'utente).
Non copiare la cartella `vigilo` dentro `/addons` assumendo che aggiorni la
stessa istanza: potresti ottenere un'altra installazione o un conflitto di slug.

## Collaudo dopo una distribuzione approvata

Eseguire nell'ordine, verificando e annotando PASS/FAIL:
1. **Avvio e dati:** l'add-on parte senza richiedere nuovo login Tesla e legge
   gli eventi precedenti; le nuove preferenze sono tutte disabilitate.
2. **Connettività:** Tailscale, Funnel, MQTT, Fleet Telemetry, token e certificati
   funzionano come prima. Non creare una seconda identità Tesla.
3. **Sentinella v1:** monitoraggio Aware/Panic e notifiche restano funzionanti.
   Vigilo 2 non deve reinviare la configurazione Fleet solo per essere stato
   installato; le opzioni nuove si attivano su richiesta.
4. **Dati letti dalla Tesla:** SOC, autonomia, BMS, pressione gomme, ricariche.
   Assenza di segnale = dati non disponibili, non errore inventato.
5. **App Android beta:** può affiancare la stabile, ma richiede backend aggiornato.
   Testare abbinamento e separazione delle preferenze.
6. **Nuovi comandi:** soltanto con auto parcheggiata, in sicurezza, senza
   occupanti e sotto supervisione. Prima verificare che server rifiuti
   comandi con auto addormentata, cambio diverso da P e telemetria vecchia.
   Successivamente testare ciascun comando singolarmente con conferma
   specifica. Non usare apertura portelloni in spazi ristretti.
7. **Riavvio:** storico e preferenze persistono; nessuna riconfigurazione Tesla
   inattesa.
8. **Ripristino:** una prova separata di recupero backup, evitando di
   sovrascrivere lo stato attivo senza ulteriori copie.

Se uno dei controlli di stabilità fallisce, interrompere i test, non attivare
altre opzioni, raccogliere solo log sanitizzati e ripristinare
l'add-on con il suo `/data` dal backup verificato.

**La prova reale e l'installazione non possono essere eseguite soltanto
tramite il repository GitHub: richiedono un collegamento autenticato a
Home Assistant e il controllo fisico dell'auto.**
