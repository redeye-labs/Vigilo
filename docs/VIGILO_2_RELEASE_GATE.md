# Vigilo 2.0-beta.1 — rilascio controllato sullo stesso add-on

Questa è una procedura di **pubblicazione**, non un installer. 
Il codice è stato compilato in GitHub Actions, ma non eseguito sulla Tesla e
non è stato installato nell'istanza privata Home Assistant.

## Stato dell'installazione osservato

La schermata fornita dal proprietario mostrava:
- Add-on Vigilo versione **1.0.9**, in esecuzione;
- **Aggiornamento disponibile** (repo main ha 1.0.10);
- **Aggiornamento automatico ATTIVO** al momento dello screenshot;
- l'utente dichiara di aver effettuato un backup HA.

Non dedurre dallo screenshot che l'utente abbia già disattivato il toggle o
scaricato/verificato il backup: questi dati non sono osservabili su GitHub.

## Protezione GitHub

- `backup/ha-main-1.0.10-before-v2`: fotografia dello stato di `main`
  prima della prima distribuzione del codice beta;
- `release/vigilo-2.0.0-beta.1`: sorgenti candidati;
- `feature/vigilo-2-foundations`: sviluppi e PR #4;
- `main`: NON aggiornare finché non sono superati i gate successivi.

I backup GitHub **non** includono il volume privato `/data` con i
token Tesla, la chiave virtuale, Tailscale e gli eventi.
Il rollback della sola versione del codice non è un ripristino completo.

## Gate obbligatori prima del merge sul branch principale

1. **Aggiornamento automatico dell'add-on spento** e confermato dall'utente.
   In caso contrario pubblicare `main` con la versione V2 potrebbe
   aggiornare e riavviare il server non presidiato.
2. Backup **completo e scaricato** di Home Assistant, comprensivo di Vigilo e
   volume privato; verifica disponibilità della chiave di ripristino.
3. Auto parcheggiata e al sicuro per la successiva sessione di collaudo; non
   autorizzare comandi che muovano o aprano parti fisiche senza conferma puntuale.
4. I test automatizzati Go, Android e **Docker build** Home Assistant devono
   superare l'ultima revisione candidata.
5. Non sostituire il repository di origine o lo slug: per aggiornare la stessa
   installazione deve essere la stessa entry Supervisor.
6. Preparare un piano di recupero della versione e del volume `/data`
   dall'interfaccia Backup di Home Assistant.

## Pubblicazione

Con i gate soddisfatti, autorizzare esplicitamente il merge della PR #4 su
`main`. La configurazione `addon/vigilo/config.yaml` contiene lo slug originale
`vigilo` e la versione `2.0.0-beta.1`. L'aggiornamento deve quindi essere
rilevato per la **stessa installazione** nel repository ufficiale
`https://github.com/redeye-labs/Vigilo`.

**Non usare** un nuovo repository/branch URL o una copia sotto `/addons`:
l'identificativo del repository è parte dell'identità dell'add-on. 
Non distribuire la copia `vigilo2_smoke`: è un server di prova senza Tesla.

Una volta disponibile l'aggiornamento, l'utente avvia manualmente l'operazione
nel pannello HA, osserva i log di avvio (senza condividere token) e verifica
nell'ordine:
1. Login, VIN, configurazione Tailscale, MQTT, Fleet Telemetry e Sentry **già
   esistenti**, senza dover effettuare nuovo abbinamento.
2. Nessun modulo V2 abilitato per impostazione predefinita e nessuna
   riconfigurazione Tesla provocata dal solo aggiornamento.
3. Continuazione eventi Aware/Panic, storico e notifiche come prima.
4. A coppie di controlli, abilitazione consapevole della raccolta BMS,
   ricarica, pneumatici; dati mancanti mostrati come non disponibili.
5. Comandi V2 soltanto successivamente e separatamente con conferma, cambio P,
   telemetria recente e veicolo presidiato.

Se i controlli da 1 a 3 falliscono: **interrompere il collaudo** e ripristinare
il backup completo dopo aver acquisito informazioni diagnostiche sanificate.

## Cosa NON equivale al completamento

Test GitHub Actions verdi dimostrano compilazione e correttezza dei test
unitari, non prestazioni reali Fleet Telemetry, compatibilità firmware
o successo del backup/restore sul server del proprietario.

Per test dettagliati vedi `docs/VIGILO_2_HA_INPLACE_TEST_PLAN.md`.
