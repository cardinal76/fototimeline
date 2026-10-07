# Guida: le foto da pCloud a FotoTimeline

I passi che servono per portare le foto in FotoTimeline e tenerle aggiornate, nell'ordine in cui
si fanno. I dettagli tecnici del server sono in [DEPLOY.md](DEPLOY.md).

```
telefono ──(app pCloud)──▶ pCloud: Automatic Upload/
                                     │ server2, ogni 6 ore (pannello "Telefoni", uno per familiare)
                                     ▼
pCloud (tutto il resto) ──(script sul PC)──▶ LifetimeCloud: FotoTimeline/AAAA/MM/GG
                                     ▲               ▲
                     LifetimeCloud: telefono/ ──(importazione automatica, ogni 15 min)
```

- **pCloud** è dove stanno oggi le foto, e dove il telefono le carica (cartella `Automatic Upload`).
- **LifetimeCloud** è l'archivio di FotoTimeline: `FotoTimeline/AAAA/MM/GG/`.
- **server2** fa girare l'app (https://foto.marcocardinali.it) e rclone, che parla con i due cloud.
- **il PC** (WSL) fa una volta sola il trasloco delle foto vecchie, leggendo pCloud Drive da `P:`.

## Da sapere prima

- **Su LifetimeCloud le foto devono entrare via WebDAV** (rclone, l'app, lo script). Quelle
  caricate dal sito o dalle app di LifetimeCloud sono cifrate nel browser: via WebDAV arrivano
  cifrate (cominciano con `LCB2`) e l'app le dà come "illeggibili".
- **Lo script cancella da pCloud solo alla fine**, dopo 7 giorni, chiedendo conferma, e solo
  quello che ha copiato e controllato in tutti e due i posti.
- **Le scansioni in JPG sono foto per lo script**: le cartelle di documenti si escludono.

---

## 1. Preparare il PC (una volta)

In WSL.

**pCloud Drive in WSL**: gli alias nel `~/.bashrc`.

```bash
cat >> ~/.bashrc <<'EOF'

# Disco P: di Windows (pCloud Drive) in WSL
alias p-up='sudo mkdir -p /mnt/p && sudo mount -t drvfs P: /mnt/p && ls /mnt/p'
alias p-down='sudo umount /mnt/p'
EOF
source ~/.bashrc
```

**Programmi**:

```bash
sudo apt install -y unzip zip libimage-exiftool-perl rclone
rclone listremotes      # deve esserci lifetime: (WebDAV di LifetimeCloud)
```

**Il repository** (serve la chiave SSH del PC su GitHub: `cat ~/.ssh/id_ed25519.pub` →
GitHub → Settings → SSH and GPG keys → New SSH key; **non** rigenerare la chiave se esiste già,
è quella dei server):

```bash
ssh -T git@github.com                         # "Hi cardinal76! ..."
git config --global core.autocrlf input       # niente "a capo" di Windows negli script
mkdir -p ~/projects && cd ~/projects
git clone git@github.com:cardinal76/fototimeline.git
```

Per aggiornarlo: `cd ~/projects/fototimeline && git pull`.

---

## 2. Trasloco delle foto vecchie da pCloud (una volta, dal PC)

Script: `deploy/foto-da-pcloud.sh`. Lavora in `~/foto-da-pcloud/`, ogni fase si rilancia e
riprende da dove era arrivata. Sempre da `~/projects/fototimeline`, con `p-up` fatto.

| Fase | Cosa fa | Tocca pCloud? |
|---|---|---|
| `anteprima` | conta foto, video e zip per cartella | no |
| `scarica` | copia sul PC, con rclone e in parallelo, foto, video e zip (esclusi a parte) | no |
| `raccogli` | dalla copia: apre gli zip, legge la data di ogni foto e la mette in `AAAA/MM/GG`, senza doppioni | no |
| `carica` | li manda su LifetimeCloud `FotoTimeline/` e controlla | no |
| `archivia` | su pCloud un zip per mese in `Archivio foto/AAAA/AAAA-MM.zip`, controllato | aggiunge |
| `pulisci` | dopo 7 giorni e con `CANCELLA`: toglie gli originali copiati | **sì** |
| `stato` | a che punto è | no |

**2.1 Anteprima** e cartelle da escludere (documenti, scansioni, cartelle senza foto):

```bash
cd ~/projects/fototimeline
RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' ./deploy/foto-da-pcloud.sh anteprima
ESCLUDI='chitarra:pCloud_lost_and_found:qnap:avvocato:banche:diplomi:documenti:salute:schede sim:consulta:isr:pd:vendite:lavoro:ecampus' ./deploy/foto-da-pcloud.sh anteprima
```

`ARCHIVIO` è una cartella **nuova** (non `/mnt/p`). `Crypto Folder` e le cartelle di sistema si
saltano sempre. Le scelte restano in `~/foto-da-pcloud/impostazioni`.

**2.2 Scarica** (rclone dal remote `pcloud:`, in parallelo: molto più veloce che leggere da `P:`;
si interrompe e si rilancia):

```bash
nohup ./deploy/foto-da-pcloud.sh scarica > ~/foto-da-pcloud/scarica.log 2>&1 &
tail -f ~/foto-da-pcloud/scarica.log
```

**Raccogli**: dalla copia scaricata, mette in ordine per data (un `raccogli` lento già
avviato da `P:` si può fermare con `pkill -f "foto-da-pcloud.sh raccogli"`: quello che ha fatto resta):

```bash
nohup ./deploy/foto-da-pcloud.sh raccogli > ~/foto-da-pcloud/raccogli.log 2>&1 &
tail -f ~/foto-da-pcloud/raccogli.log        # Ctrl+C chiude solo il tail
./deploy/foto-da-pcloud.sh stato
```

Gli zip con dentro altri file e i file non letti restano su pCloud: sono in
`~/foto-da-pcloud/da-controllare.txt`.

Raccogli prepara **4 zip insieme** (li apre, legge le date, calcola le impronte), uno per
core, e li mette in `ordinate/` uno alla volta nell'ordine di sempre: stessi nomi e stessi
file di un giro in fila. Per cambiare il numero si mette `PARALLELI` davanti, a ogni
lancio (non si ricorda): `PARALLELI=2` se il PC serve per altro o il disco è quasi pieno
(restano aperti al massimo `PARALLELI`+1 zip insieme), `PARALLELI=1` per fare tutto in fila.

```bash
PARALLELI=2 nohup ./deploy/foto-da-pcloud.sh raccogli > ~/foto-da-pcloud/raccogli.log 2>&1 &
```

Per fermarlo: `pkill -f "foto-da-pcloud.sh raccogli"` (si fermano anche i 4 al lavoro).
Rilanciato, riprende: uno zip conta come fatto solo quando è in `fonti.tsv`.

**2.3 Carica** su LifetimeCloud, poi nell'app **Monta** e **Indicizza archivio**:

```bash
./deploy/foto-da-pcloud.sh carica
```

**2.4 Archivia** su pCloud (uno zip per mese):

```bash
./deploy/foto-da-pcloud.sh archivia
```

**2.5 Dopo 7 giorni**, guardata la timeline e gli zip in `Archivio foto`:

```bash
./deploy/foto-da-pcloud.sh pulisci           # mostra cosa toglie e chiede CANCELLA
rm -rf ~/foto-da-pcloud/ordinate ~/foto-da-pcloud/specchio   # le copie sul PC non servono più
```

---

## 3. Foto nuove dal telefono (automatico, su server2)

Il telefono carica su pCloud `Automatic Upload`; l'app, ogni 6 ore (o a richiesta), copia i file
nuovi in LifetimeCloud `telefono/`; l'importazione automatica li sposta in
`FotoTimeline/AAAA/MM/GG`; dopo 7 giorni li toglie da pCloud, solo se importati.

**3.1 Collegare pCloud a rclone (una volta).** Il login si fa sul PC, dove c'è il browser e rclone
sceglie da solo il server europeo:

Su **server2**, se c'è un remote `pcloud` rotto:

```bash
docker run --rm -it --user $(id -u):$(id -g) -e XDG_CACHE_HOME=/tmp -v ~/fototimeline/rclone:/config/rclone rclone/rclone:1.68 config delete pcloud
```

Sul **PC**: `rclone config` → `n` → nome `pcloud` → tipo `pcloud` → client_id e secret vuoti →
advanced `n` → browser **`y`** (login) → `y` → `q`. Prova e mostra la sezione:

```bash
rclone lsd "pcloud:Automatic Upload"         # le cartelle dei dispositivi
rclone config show pcloud                    # deve avere hostname = eapi.pcloud.com
```

Copia la sezione `[pcloud]` (contiene il token: non va incollata altrove) e su **server2** in
fondo a `~/fototimeline/rclone/rclone.conf` (`nano`, riga vuota, incolla, `Ctrl+O`, `Ctrl+X`). Poi:

```bash
sed -E 's/(token|pass) = .{12}.*/\1 = ***/' ~/fototimeline/rclone/rclone.conf   # [lifetime] e [pcloud], una volta
docker restart fototimeline-rclone-1
sleep 3
docker exec fototimeline-rclone-1 rclone lsd "pcloud:Automatic Upload" --config /config/rclone/rclone.conf
```

**3.2 Nell'app** (https://foto.marcocardinali.it, da admin): **Monta** (il riavvio di rclone lo
smonta) → **Telefoni** → **Aggiungi** → nome `Telefono di Marco`, proprietario `marco`, cartella
`pcloud:Automatic Upload`, ogni `6` ore, togli dopo `7` giorni → **Attivo** → **Aggiungi** →
**Sincronizza ora**. Le foto che arrivano da lì risultano "caricate da" Marco.

Se la sincronizzazione c'era già da prima (un telefono solo), non serve aggiungerlo: dopo il
rilascio compare da solo come "Telefono", con le sue impostazioni, e non ricopia niente. È di
`FOTOTIMELINE_TELEFONO_PROPRIETARIO` (nel `.env`) o, se vuoto, del primo amministratore che entra;
da **Modifica** gli si cambia nome e proprietario.

Controllo da server2:

```bash
docker exec fototimeline-rclone-1 rclone size lifetime:telefono --config /config/rclone/rclone.conf
docker logs --since 10m fototimeline-app-1 2>&1 | grep -iE 'telefono|importazione' | tail
```

---

## 4. L'app, ogni giorno

- **Cloud smontato / Monta**: dopo ogni rilascio o riavvio il cloud è smontato; la timeline si
  vede, ma originali e importazioni vogliono il cloud montato.
- **Carica foto**: dal browser, direttamente in archivio. Dal telefono Android anche con
  **Condividi → FotoTimeline** dalla galleria (qui sotto).
- **Importa cartella**: una cartella di LifetimeCloud (caricata via WebDAV), con "sposta".
- **Indicizza archivio**: per le foto messe a mano in `FotoTimeline/AAAA/MM/GG`.
- **Converti video**: una volta, dopo il trasloco o l'indicizzazione, per i video vecchi in HEVC
  (iPhone) che Firefox e Chrome non sempre riproducono. La coda gira in sottofondo, uno alla volta
  (in basso a sinistra quanti fatti e quanti da fare); i video nuovi ci entrano da soli. Col cloud
  smontato aspetta. Su un VPS conta qualche minuto di CPU per ogni minuto di video 4K.
- **Telefoni**: la sincronizzazione da pCloud, un telefono per familiare (sezione 5).
- **Google Foto**: scegliere qualche foto dal proprio Google Foto, e (admin) importare tutta la
  libreria con Google Takeout (sezione 6).
- **Caricate da**: il filtro per vedere le foto portate da una persona; nel visore, "Caricata da".
- **Backup**: copia dei metadati in `FotoTimeline/.backup/` (anche da sola, una al giorno).
- **Per contenuto** (accanto al campo di ricerca): si scrive cosa c'è nella foto, "spiaggia",
  "cane", "torta di compleanno", e si trovano le foto anche senza tag.
- **Indicizza contenuto** (admin): la prima volta, dopo il trasloco, manda tutte le foto alla
  ricerca per contenuto. Con 150.000 foto ci vogliono ore (anche una notte): va avanti da solo,
  anche col cloud smontato, e se il server riparte riprende. Le foto nuove entrano da sole.
- **Calcola luoghi**: dà il nome del posto (Sperlonga, Lazio · Italia) alle foto col GPS che non
  ce l'hanno; quelle nuove lo prendono da sole. Non serve il cloud montato, 150 mila foto in meno
  di un minuto. Dopo il primo rilascio con i luoghi va lanciato una volta; poi il filtro
  **📍 Luogo** nella barra sceglie nazione, regione o paese (o lo cerca per nome).
- **Condividere con chi non ha un account**: *Seleziona* le foto (o apri un album), *Condividi…*
  (o *Condividi album*), scegli titolo e scadenza, *Crea link*, *Copia* e mandalo su WhatsApp. Chi lo
  apre vede solo quelle foto, senza login. Spunta "Permetti di scaricare" solo se servono gli
  originali (hanno dentro i dati della fotocamera e la posizione). I link creati, con le visite, sono in
  *Condivisioni*, da dove si revocano. Col cloud smontato chi ha il link vede solo le miniature.
- **Quasi uguali**: raffiche e copie della stessa foto (ridimensionate, passate da WhatsApp). Per
  ogni gruppo la suggerita è segnata **Tieni**; un clic su una foto cambia tieni/togli, poi
  **Togli le altre** (chiede conferma; serve il cloud montato), **Non sono doppioni** o **Salta**.
  Le preferite non si propongono mai da togliere. La prima volta, da admin, **Calcola impronte**
  (nel dialogo) per le foto già in archivio: legge solo le miniature, non serve il cloud. Le foto
  nuove la prendono da sole.
- **Salute**: il pallino dice se va tutto bene (verde), se c'è da guardare (giallo) o se qualcosa
  non va (rosso); il dialogo spiega cosa, voce per voce. Con il bot di Telegram (DEPLOY.md,
  "Salute e avvisi su Telegram") arriva un messaggio quando una voce diventa rossa e quando torna
  a posto: per esempio se il token di pCloud scade, o se il backup non si fa da due giorni.
- **Ricordi su Telegram**: ogni mattina alle 8 il bot manda le foto di oggi negli anni passati
  ("📅 5 ottobre · 3 anni fa a Sperlonga"), con il link **Tutte le foto di oggi** che apre l'app su
  "Accadde oggi". Nei giorni senza foto non arriva niente. Si cambiano in **Salute → Ricordi su
  Telegram** (admin): acceso o spento, l'ora, quante foto, "Nessun ricordo oggi"; **Manda ora una
  prova** li manda subito. Le foto partono ridotte e senza dati GPS; nessun link pubblico.

### Dal telefono: Condividi → FotoTimeline

Per mandare nell'archivio qualche foto o video scelti a mano (una foto di WhatsApp, quelle di una
festa) senza aspettare la sincronizzazione da pCloud. Funziona su **Android con Chrome** (o Edge,
Samsung Internet): è l'app installata che compare tra quelle a cui condividere.

1. **Una volta**: apri il sito in Chrome, entra col tuo utente, menu ⋮ → **Installa app** (o
   **Aggiungi a schermata Home** → Installa). Apri l'app installata almeno una volta. Compare con
   l'icona di FotoTimeline e il nome **Foto**.
2. Dalla galleria (Google Foto, Galleria Samsung o Xiaomi, File...) seleziona foto e video →
   **Condividi** → **Foto** (l'icona di FotoTimeline).
3. Si apre l'app con **Carica N foto dal telefono**: le anteprime, un album se vuoi (suggerisce
   quelli esistenti), **Carica**. Vanno su una alla volta, con la barra; alla fine quante nuove,
   quante c'erano già e gli errori.

- Se la sessione è scaduta si passa dal login: le foto aspettano sul telefono e il dialogo torna
  dopo. Aspettano anche con **Più tardi**, se si chiude l'app o se il **cloud è smontato** (il
  dialogo lo dice; un amministratore lo può montare da lì): riaprendo l'app ricompare. Dopo un
  giorno quelle mai caricate si buttano. **Annulla** le toglie subito.
- Se FotoTimeline non compare tra le app di "Condividi": Chrome aggiorna l'app installata da solo,
  ma può metterci un giorno; altrimenti toglila dalla Home e reinstallala.
- Se compare "La condivisione non è arrivata all'app": l'app non era ancora pronta a riceverla
  (prima apertura). Riaprila dalla Home e condividi di nuovo.
- **iPhone (Safari)**: Apple non permette alle app web di comparire in "Condividi". Si usa **Carica
  foto** dall'app: apre la galleria del telefono e si scelgono lì le foto.
- I file salgono dalla rete del telefono: per tanti video meglio il Wi-Fi.

Le modifiche all'app: pull request su `main`, poi il workflow **Rilascio** (Actions → Rilascio →
Run workflow) la mette su server2.

---

## 5. Aggiungere un familiare

L'archivio è di famiglia: tutti vedono tutte le foto, ma ognuno ha il suo telefono, e ogni foto
sa chi l'ha portata. Per aggiungere Anna:

**5.1 L'utente in Keycloak**, dal server di produzione (i comandi completi sono in
[DEPLOY.md](DEPLOY.md), "Keycloak: realm, client e amministratore"; per la console web c'è
`tunnel-kc-up`):

```bash
cd ~/presenze && set -a && . ./.env.prod && set +a
KC="docker exec presenze-keycloak /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8080/auth --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"
$KC create users -r fototimeline -s username=anna -s enabled=true \
  -s firstName=Anna -s lastName=Rossi -s email=anna@example.com
$KC set-password -r fototimeline --username anna --new-password 'temporanea' --temporary
# Solo se deve montare il cloud, fare backup e gestire i telefoni di tutti:
# $KC add-roles -r fototimeline --uusername anna --rolename fototimeline-admin
```

Anna entra una volta su https://foto.marcocardinali.it (cambia la password): da quel momento
l'app la conosce e la propone come proprietario. Senza ruolo admin vede e modifica le foto, ma dei
telefoni vede solo il suo.

**5.2 La sua cartella su pCloud.** Due casi:

- **Stesso account pCloud** (quello di famiglia, il remote `pcloud`): sul suo telefono l'app pCloud
  carica in `Automatic Upload/<nome del dispositivo>`. Se vuoi un telefono a parte per lei, la
  cartella è `pcloud:Automatic Upload/<nome del dispositivo>`, e quella del telefono di Marco va
  ristretta allo stesso modo (`pcloud:Automatic Upload/Pixel 8`): l'app non accetta due telefoni
  sulla stessa cartella né una cartella dentro l'altra, perché copierebbero gli stessi file.
- **Il suo account pCloud**: serve un **altro remote di rclone**, `pcloud-anna`, fatto come
  `pcloud` nella sezione 3.1 ma col login di Anna. Sul **PC**: `rclone config` → `n` → nome
  `pcloud-anna` → tipo `pcloud` → client_id e secret vuoti → advanced `n` → browser **`y`** (il
  login lo fa Anna, col suo account) → `y` → `q`. Poi:

  ```bash
  rclone lsd "pcloud-anna:Automatic Upload"    # le cartelle dei suoi dispositivi
  rclone config show pcloud-anna               # deve avere hostname = eapi.pcloud.com
  ```

  Copia la sezione `[pcloud-anna]` in fondo a `~/fototimeline/rclone/rclone.conf` su **server2**,
  poi `docker restart fototimeline-rclone-1` e prova:

  ```bash
  docker exec fototimeline-rclone-1 rclone lsd "pcloud-anna:Automatic Upload" --config /config/rclone/rclone.conf
  ```

  La cartella del suo telefono è `pcloud-anna:Automatic Upload`.

**5.3 Nell'app**, da admin: **Monta** (se il riavvio di rclone l'ha smontato) → **Telefoni** →
**Aggiungi** → nome `Telefono di Anna`, proprietario `anna`, la cartella del punto 5.2, **Attivo**
→ **Aggiungi** → **Sincronizza ora**. Le sue foto arrivano nella stessa cartella automatica di
tutte le altre (senza album nuovi) e risultano "caricate da Anna". Se un giorno lo togli
(**Elimina**), le copie restano registrate: rimesso con la stessa cartella non ricopia niente.

## 6. Google Foto

Da marzo 2025 Google non lascia più alle app leggere tutta la libreria di Google Foto. Quindi:

- **tutta la libreria** (una volta, e poi ogni due mesi per un anno) passa da **Google Takeout**:
  Google prepara degli zip su Google Drive e l'app li importa da sola (6.1–6.3, da admin);
- **qualche foto ogni tanto** si sceglie con **Scegli da Google Foto**: ognuno col suo account
  Google, dall'app (6.4 una volta da admin, 6.5 per tutti).

Le foto già presenti si saltano sempre (stesso contenuto = stessa foto), quindi non c'è rischio di
doppioni se una foto arriva sia dal telefono sia da Google.

**6.1 Programmare Takeout su Drive (una volta, dal PC).**

1. Controlla quanto spazio libero c'è su **Google Drive** (gli zip occupano spazio lì) e su
   **server2** (ci si scarica uno zip alla volta):

   ```bash
   df -h ~      # su server2: "Avail" deve superare la dimensione di uno zip + 2 GB
   ```

2. Apri https://takeout.google.com con l'account di Google Foto.
3. **Deseleziona tutto**, poi spunta solo **Google Foto** (lascia "Tutti gli album di foto
   inclusi") → **Passaggio successivo**.
4. **Metodo di invio**: **Aggiungi a Drive**.
5. **Frequenza**: **Esporta ogni 2 mesi per 1 anno**.
6. **Tipo di file**: **.zip** (non .tgz). **Dimensione**: **50 GB**; se su server2 lo spazio
   libero è meno di 52 GB scegli 10 GB o 20 GB (più zip, ma più piccoli).
7. **Crea esportazione**. Google manda un'email quando è pronta (anche giorni): gli zip sono in
   Drive nella cartella **Takeout**.

**6.2 Collegare Google Drive a rclone (una volta).** Come per pCloud (3.1): il login si fa sul
**PC**, dove c'è il browser; su server2 si copia solo la sezione del `rclone.conf`.

Sul **PC**: `rclone config` → `n` → nome **`gdrive`** → tipo **`drive`** (Google Drive) →
client_id e client_secret **vuoti** → scope **`1`** (accesso completo: serve per togliere gli zip
vecchi; se non li vuoi mai togliere dall'app basta `2`, sola lettura) → service_account_file
vuoto → advanced `n` → browser **`y`** (login con l'account di Google Foto, **Consenti**) → shared
drive **`n`** → `y` → `q`. Prova:

```bash
rclone lsf gdrive:Takeout           # gli zip di Takeout (vuoto finché Google non ha finito)
rclone config show gdrive           # la sezione da copiare
```

Copia la sezione `[gdrive]` (contiene il token: **non va incollata in chat** né altrove) e su
**server2** in fondo a `~/fototimeline/rclone/rclone.conf` (`nano`, riga vuota, incolla,
`Ctrl+O`, `Ctrl+X`). Poi:

```bash
mkdir -p ~/fototimeline/takeout      # qui si scarica uno zip alla volta (il rilascio la crea da solo)
docker restart fototimeline-rclone-1
sleep 3
docker exec fototimeline-rclone-1 rclone lsf gdrive:Takeout --config /config/rclone/rclone.conf
```

**6.3 Nell'app**, da admin (https://foto.marcocardinali.it): **Monta** (il riavvio di rclone lo
smonta) → **Google Foto** → riquadro **Importa da Google Takeout**:

- **Dove sono gli zip**: `gdrive:Takeout`;
- **Le foto sono "caricate da"**: chi le ha fatte (per esempio `marco`), o vuoto = chi preme Avvia;
- **Togli gli zip da Drive dopo quanti giorni**: `0` = mai (consigliato la prima volta); per
  esempio `14` per liberare Drive due settimane dopo un'importazione senza errori;
- **Salva** → **Avvia**.

L'app cerca gli zip, ne scarica uno, lo apre e importa foto e video uno alla volta, poi lo cancella
da server2 e passa al successivo. Si può chiudere la pagina: va avanti sul server; il riquadro
mostra zip X di N, i file, e i contatori (nuove, già presenti, senza JSON, saltati, errori). Data,
luogo, descrizione, preferite e album vengono dai file JSON di Google quando la foto non li ha
già. **Annulla** si ferma dopo il file in corso; **Avvia** riparte da lì. Dopo un riavvio del
server riparte da solo; col cloud smontato aspetta. Ogni due mesi, quando arriva l'email di Google,
basta **Avvia**: gli zip già fatti si saltano. Se uno zip ha errori la pagina **Salute** lo dice;
nel riquadro **Riprova** lo rifà.

**6.4 Credenziali Google per "Scegli da Google Foto" (una volta, da admin, dal PC).**

1. Apri https://console.cloud.google.com con il tuo account Google → in alto **Seleziona un
   progetto** → **Nuovo progetto** → nome `FotoTimeline` → **Crea** (e selezionalo).
2. **API e servizi** → **Libreria** → cerca **Google Photos Picker API** → **Abilita**.
3. **API e servizi** → **Schermata consenso OAuth** (o **Google Auth Platform**) → **Inizia**:
   nome app `FotoTimeline`, email di assistenza la tua → pubblico **Esterno** → la tua email →
   **Crea**.
4. **Pubblico** (Audience): lascia lo stato **Test** e in **Utenti di prova** aggiungi l'indirizzo
   Google di ogni familiare che userà la funzione (fino a 100).
5. **Accesso ai dati** (Data access) → **Aggiungi o rimuovi ambiti** → cerca `photospicker` →
   spunta `.../auth/photospicker.mediaitems.readonly` → **Aggiorna** → **Salva**.
6. **Client** → **Crea client** → tipo **Applicazione web** → nome `FotoTimeline` → **URI di
   reindirizzamento autorizzati** → **Aggiungi URI** → `https://foto.marcocardinali.it/api/google/callback`
   → **Crea**.
7. Compaiono **ID client** e **Client secret**: copiali **direttamente** nel `.env` di server2,
   mai in chat:

   ```bash
   nano ~/fototimeline/.env
   # FOTOTIMELINE_GOOGLE_CLIENT_ID=....apps.googleusercontent.com
   # FOTOTIMELINE_GOOGLE_CLIENT_SECRET=GOCSPX-...
   # FOTOTIMELINE_GOOGLE_CHIAVE=   ← incolla il risultato di: openssl rand -base64 32
   ```

8. Rifai partire l'app col nuovo `.env` (va bene anche il prossimo rilascio):

   ```bash
   cd ~/actions-runner-fototimeline/_work/fototimeline/fototimeline
   docker compose -p fototimeline -f deploy/docker-compose.yml --env-file ~/fototimeline/.env up -d
   ```

Con l'app in stato **Test** Google fa scadere il collegamento dopo 7 giorni: l'app lo dice
("ricollega Google") e basta ripremere **Collega Google**. Per non doverlo rifare si può pubblicare
l'app (**Pubblico** → **Pubblica app**): Google mostra un avviso "app non verificata", che per la
famiglia va bene.

**6.5 Scegliere le foto (chiunque, dall'app).** **Google Foto** → **Collega Google** → scegli
l'account → (se compare "Google non ha verificato questa app": **Continua**) → **Continua** sul
permesso "vedere le foto che selezioni" → si torna all'app ("Google collegato"). Poi **Scegli
foto**: si apre una scheda di Google Foto, scegli le foto (fino a 2000 per volta) e premi
**Fine**; la scheda si chiude e l'app le scarica e le importa, a tuo nome ("Caricate da"). La
barra dice quante ne mancano; quelle già presenti si saltano. **Scollega Google** toglie il
permesso (anche su Google) e cancella il collegamento dall'app.

---

## Da Amazon Foto (una volta, dal PC)

Amazon Foto non ha più un'API per sviluppatori: nessun programma (nemmeno rclone) ci si collega.
Si scarica la libreria sul PC con l'app di Amazon Foto per Windows e lo script
`deploy/foto-da-cartella.sh` la passa dallo stesso giro del trasloco da pCloud: data di ogni foto,
`AAAA/MM/GG`, niente doppioni, carica su LifetimeCloud e controllo. Lavora in `~/foto-da-amazon/`,
tutto suo: non tocca `~/foto-da-pcloud/` (lo legge soltanto) e non cancella niente, né sul PC né
su Amazon.

- **Prima finisci `raccogli` di pCloud** (sezione 2; meglio anche `carica`). Lo script legge
  `~/foto-da-pcloud/impronte.tsv`: le foto che pCloud ha già non le riprende, e i loro nomi non li
  riusa, così su LifetimeCloud una foto non ne copre un'altra.
- **Spazio**: la libreria scaricata su `C:` e, durante il giro, un'altra copia in
  `~/foto-da-amazon/ordinate` (anche questa su `C:`, nel disco di WSL). Servono circa due volte la
  dimensione della libreria che l'app di Amazon dice prima di scaricare.
- **Le date**: dall'EXIF della foto o del video; se manca, dal nome (`IMG_20190101_…`,
  `IMG-20190101-WA0001`, `Screenshot 2019-01-01 …`); se manca anche quello, dalla data del file,
  che dopo un download può essere il giorno del download: `raccogli` elenca quei giorni.
- **I doppioni del download** (`IMG_0001 (1).jpg`, la stessa foto in un anno e in un album)
  entrano una volta sola, col nome senza ` (1)`.

**A.1 Scaricare la libreria (su Windows).** Le voci dell'app possono cambiare un po' da una versione
all'altra:

1. Installa **Amazon Photos per Windows** (da amazon.it → Amazon Foto → App, "Scarica per
   desktop") ed entra col tuo account Amazon.
2. Nel menu a sinistra **Scarica** (Download) → **Scarica cartelle** → scegli **tutto** (tutte le
   foto e i video) → come destinazione una cartella nuova, per esempio
   `C:\Users\<tuo utente>\Pictures\Amazon Photos Downloads` → **Scarica**.
3. Lascia il PC acceso (niente sospensione) finché l'app non ha finito; se si ferma, rilancia lo
   stesso download.

In alternativa, dal sito (amazon.it/photos): si selezionano le foto e si scaricano in zip, ma al
massimo 1000 file o 5 GB per volta. Per decine di migliaia di foto va bene solo per i pezzi
mancanti: gli zip si mettono nella stessa cartella, lo script li apre da solo. La richiesta dei
dati personali di Amazon non contiene le foto.

**A.2 Il repository aggiornato e la cartella vista da WSL.** Il `git pull` non disturba una fase di
`foto-da-pcloud.sh` che sta girando, e quello script fa esattamente quello che faceva prima.

```bash
cd ~/projects/fototimeline && git pull
ls /mnt/c/Users                               # il nome del tuo utente di Windows
ls "/mnt/c/Users/NOME/Pictures/Amazon Photos Downloads" | head
du -sh "/mnt/c/Users/NOME/Pictures/Amazon Photos Downloads"
df -h ~                                       # spazio libero per la copia in ~/foto-da-amazon
```

**A.3 Anteprima**: quante foto, video e zip per cartella (le cartelle da saltare con `ESCLUDI`,
separate da `:`). La cartella si ricorda in `~/foto-da-amazon/impostazioni`:

```bash
SORGENTE='/mnt/c/Users/NOME/Pictures/Amazon Photos Downloads' ./deploy/foto-da-cartella.sh anteprima
```

**A.4 Raccogli** (si interrompe e si rilancia, riprende da dove era):

```bash
nohup ./deploy/foto-da-cartella.sh raccogli > ~/foto-da-amazon/raccogli.log 2>&1 &
tail -f ~/foto-da-amazon/raccogli.log        # Ctrl+C chiude solo il tail
./deploy/foto-da-cartella.sh stato
```

Anche qui 4 zip si preparano insieme; `PARALLELI=1` davanti per farli in fila (vedi 2.2).

**A.5 Controllare** prima di caricare:

- in fondo a `raccogli.log`: quante foto diverse, quanti doppioni di pCloud saltati e i **giorni
  recenti** (ultimi 60 giorni) con dentro qualcosa: se non sono foto di quei giorni, hanno la data
  del download. Guardale e spostale a mano nella cartella giusta di `~/foto-da-amazon/ordinate`
  (o lasciale: nell'app la data si corregge anche dopo);
- `cat ~/foto-da-amazon/da-controllare.txt` (se c'è): file non letti, zip rovinati o con dentro
  altro. Restano dove sono;
- le cartelle in Esplora risorse: `explorer.exe "$(wslpath -w ~/foto-da-amazon/ordinate)"`.

**A.6 Carica** su LifetimeCloud, poi nell'app **Monta** e **Indicizza archivio**:

```bash
./deploy/foto-da-cartella.sh carica
```

**A.7 Facoltativo, archivia**: uno zip per mese in una cartella a scelta (meglio una nuova, non
quella di pCloud):

```bash
ARCHIVIO='/mnt/p/Archivio foto Amazon' ./deploy/foto-da-cartella.sh archivia
```

**A.8 Alla fine**, guardata la timeline: `rm -rf ~/foto-da-amazon/ordinate`. La cartella scaricata
su `C:` e le foto su Amazon restano come sono: si tolgono a mano, quando vuoi.

Lo stesso script va bene per qualsiasi cartella con foto sciolte e zip (un disco esterno, un vecchio
backup): un'altra cartella di lavoro per ogni giro, per esempio
`LAVORO=~/foto-da-disco SORGENTE=/mnt/e/Foto ./deploy/foto-da-cartella.sh anteprima` (e poi
`LAVORO=~/foto-da-disco` davanti a ogni comando). I test di tutti e due gli script:
`./deploy/test-foto.sh`.

---

## Se qualcosa non va

| Messaggio | Causa | Rimedio |
|---|---|---|
| "Immagine illeggibile", il file comincia con `LCB2` | caricato dal sito/app di LifetimeCloud: cifrato | ricaricarlo via WebDAV (rclone, app, script) |
| "Montaggio non riuscito", nel log rclone `directory already mounted` | versione vecchia dell'app | rilasciare `main` |
| `Permission denied` su `/miniature/...` | volume miniature di root | rilasciare `main` (il rilascio lo sistema) |
| `bash\r: No such file or directory` | script con "a capo" di Windows | `git config --global core.autocrlf input`, poi `git rm -rq --cached . && git reset -q --hard` |
| `./deploy/foto-da-pcloud.sh: No such file or directory` | non sei nella cartella del repository | `cd ~/projects/fototimeline` |
| `Permission denied (publickey)` con git | chiave del PC non su GitHub | sezione 1, chiave su GitHub |
| pCloud `empty token found` | il token non è stato incollato | rifare 3.1 (o 5.2 per `pcloud-anna`) |
| "remote sconosciuto" o `didn't find section in config file` per `pcloud-anna:` | il remote non è in `rclone.conf` di server2, o rclone non è stato riavviato | rifare 5.2 |
| "La cartella ... è già di ..." | due telefoni sulla stessa cartella o una dentro l'altra | restringere le cartelle per dispositivo (5.2) |
| pCloud `Invalid 'access_token' (2094)` | account europeo senza `hostname = eapi.pcloud.com` | rifare 3.1 dal PC |
| "ARCHIVIO non può essere la radice" | `ARCHIVIO=/mnt/p` | una cartella nuova, es. `/mnt/p/Archivio foto` |
| raccogli fermo | PC in sospensione o `P:` staccato | `p-up` e rilanciare: riprende |
| "La ricerca per contenuto non risponde" | il container visione riparte o è fermo | `docker logs fototimeline-visione-1` su server2; si riprova dopo un minuto |
| il rilascio dice "Manca VISIONE_SEGRETO" | `.env` di prima della ricerca per contenuto | aggiungere `VISIONE_SEGRETO=$(openssl rand -hex 32)` in `~/fototimeline/.env` |
| scarica: `file name too long` | su pCloud un nome oltre 255 byte (didascalie lunghe), che il disco di Linux non accetta | `git pull` e rilanciare `scarica`: quei file arrivano col nome accorciato (`…~1a2b3c4d.jpg`), e `pulisci` cancella comunque l'originale |
| Takeout: "Spazio insufficiente in /takeout ..." | su server2 non c'è posto per lo zip | liberare spazio (`df -h ~`) o rifare Takeout con zip più piccoli (6.1), poi **Avvia** |
| Takeout: `didn't find section in config file` per `gdrive:` | il remote non è in `rclone.conf` di server2, o rclone non è stato riavviato | rifare 6.2 |
| Takeout: "Qui non si può: ... senza rclone" | app sul PC, non sul server | il Takeout si importa solo da server2 |
| Takeout: tante foto "senza JSON" | normale per qualche file (Google non lo mette sempre) | niente: la data viene dall'EXIF o dal file |
| Google: "ricollega Google" o `invalid_grant` | app Google in stato Test (7 giorni) o permesso tolto | **Collega Google** di nuovo (6.5) |
| Google: `Errore 400: redirect_uri_mismatch` | l'URI nelle credenziali non è identico | rifare 6.4 punto 6: `https://foto.marcocardinali.it/api/google/callback` |
| Google: "Accesso bloccato: l'app non ha completato la verifica" | l'email non è tra gli utenti di prova | aggiungerla (6.4 punto 4) |
| Nessun pulsante **Google Foto** per i familiari | `FOTOTIMELINE_GOOGLE_CLIENT_ID`/`SECRET` vuoti nel `.env` | 6.4, punti 7 e 8 |
