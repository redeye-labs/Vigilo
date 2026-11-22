# Firma APK Vigilo da smartphone

Questa procedura va eseguita **una sola volta**. La chiave privata resta esclusa dal codice e dagli artifact GitHub.

## 1. Apri un terminale GitHub Codespaces dal browser
Apri https://github.com/redeye-labs/Vigilo, scegli **Code → Codespaces → Create codespace on main** e apri il terminale. Codespaces potrebbe richiedere abilitazione sull'account.

## 2. Genera la nuova chiave
Esegui le righe qui sotto nel terminale. Non pubblicare né inviare a nessuno la password.

```bash
cd /workspaces/Vigilo
read -r -s -p "Nuova password di firma (conservala): " PASS; echo
test "${#PASS}" -ge 12 || { echo "Usa almeno 12 caratteri"; unset PASS; exit 1; }
umask 077
keytool -genkeypair -noprompt -keystore vigilo-release.jks -alias vigilo -keyalg RSA -keysize 3072 -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Vigilo, O=RedEye Labs, C=IT"
```

**Salva una copia di `vigilo-release.jks` sul telefono e un secondo backup fuori da GitHub**, usando nel file explorer di Codespaces **Download** sul file. Non aggiungerlo mai con `git add`.

## 3. Registra i segreti Actions
Nel terminale prova:

```bash
base64 -w 0 vigilo-release.jks | gh secret set VIGILO_KEYSTORE_BASE64 -R redeye-labs/Vigilo
printf '%s' "$PASS" | gh secret set VIGILO_KEYSTORE_PASSWORD -R redeye-labs/Vigilo
printf 'vigilo' | gh secret set VIGILO_KEY_ALIAS -R redeye-labs/Vigilo
unset PASS
```

Se `gh` chiede autorizzazioni, usa `gh auth login` seguendo la procedura nel browser. Se l'account Codespaces non consente di aggiornare Actions Secrets, gestiscili manualmente da **Repository Settings → Secrets and variables → Actions**, senza esporli in commit o commenti.

Verifica che siano presenti **i tre nomi** dei segreti nella pagina Settings (i valori non devono essere visibili).

## 4. Compila e pubblica
Apri **Actions → Vigilo APK firmato → Run workflow** (ramo main). Per la prima compilazione scegli `publish_release = false`, verifica il risultato, poi puoi ripetere con `publish_release = true`.

Il file `Vigilo-Release-APK` apparirà in Artifacts; dopo la pubblicazione ufficiale il link stabile sarà https://github.com/redeye-labs/Vigilo/releases/latest/download/Vigilo.apk.

## Importante
- La nuova chiave **non** aggiorna le app firmate con quella vecchia: disinstalla prima la vecchia Vigilo, dopo aver conservato le impostazioni/QR utili.
- La firma debug di GitHub Actions non è stabile tra runner e non deve essere usata per gli aggiornamenti.
- Ogni release successiva richiede **la stessa chiave privata** e un `versionCode` crescente.
- Non usare workflow che pubblicano la chiave `.jks` tra gli Artifacts: gli artifact possono essere accessibili ad altri utenti.
