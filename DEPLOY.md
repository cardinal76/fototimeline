# Rilascio su server2

FotoTimeline gira su **server2** (Contabo, `ssh server2`), la stessa macchina
dei runner di build di presenze e TrovaCampo. Le foto **non** stanno sul disco
di server2: stanno su **LifetimeCloud** (o un altro cloud supportato da
rclone), che un container rclone monta quando un amministratore lo chiede
dall'app.

```
internet ─▶ caddy (HTTPS, Let's Encrypt) ─▶ app (Spring Boot + Angular) ─┬─▶ mongo
                                                                          └─▶ rclone rcd ─WebDAV─▶ LifetimeCloud
login: Keycloak di presenze, realm "fototimeline"
```

| Cosa | Dove |
|---|---|
| Originali | LifetimeCloud, cartella `FotoTimeline/AAAA/MM/GG/` |
| Foto da importare | qualunque cartella di LifetimeCloud (l'app vede la radice come `/cloud`) |
| Miniature | volume Docker `fototimeline_miniature` su server2 (si rigenerano dagli originali) |
| Video compatibili (H.264) | LifetimeCloud, `FotoTimeline/.compatibili/<id>.mp4` (si rifanno con "Converti video") |
| Metadati (date, tag, album) | MongoDB, volume `fototimeline_mongo` su server2 |
| Configurazione e segreti | `~/fototimeline/.env` e `~/fototimeline/rclone/rclone.conf` su server2 |

Il rilascio lo fa `.github/workflows/rilascio.yml` sul runner `fototimeline-build`
(vedi "CI su server2" nel README): a ogni push sul branch `produzione`, o a
mano dalla scheda Actions. Il runner sta sulla stessa macchina dell'app, quindi
niente registro di immagini né SSH: `docker compose up --build` e basta.

## Montato e smontato

- **Smontato** (dopo ogni rilascio o riavvio, salvo `RCLONE_MONTA_ALL_AVVIO=true`):
  la timeline si vede, perché miniature e metadati sono su server2; originali,
  caricamenti, importazioni, cambi di data ed eliminazioni rispondono "archivio
  smontato". Il server non raggiunge le foto, e niente viene scritto per
  sbaglio sul suo disco.
- **Montato**: tutto funziona. Lo si monta e smonta dall'app (in alto a destra),
  solo con il ruolo `fototimeline-admin`.

rclone gira in un container suo: è l'unico con i permessi FUSE e con il token
di LifetimeCloud. L'app gli parla con l'API di controllo (`rclone rcd`), solo
sulla rete interna di Docker e con password.

## Una volta sola

### 1. DNS e porte

Un record `A` per il dominio (per esempio `foto.marcocardinali.it`) con l'IP di
server2. Su server2 le porte **80** e **443** devono essere aperte (Caddy
chiede il certificato a Let's Encrypt al primo avvio):

```bash
ssh server2 'sudo ufw status'          # se ufw è attivo:
ssh server2 'sudo ufw allow 80,443/tcp && sudo ufw allow 443/udp'
```

### 2. rclone e LifetimeCloud

Su server2, la cartella di lavoro:

```bash
ssh server2 'mkdir -p ~/fototimeline/rclone ~/fototimeline/cloud && chmod 700 ~/fototimeline'
```

La configurazione di rclone si fa sul PC (dove `rclone` c'è già) e si copia: è
lo stesso `rclone config create` della prova. Dal profilo di LifetimeCloud,
sezione **WebDAV / rclone access**: URL `https://lifetimecloud.me/dav/<utente>/`,
utente e token.

```bash
# sul PC
rclone config create lifetime webdav \
  url=https://lifetimecloud.me/dav/<utente>/ vendor=other \
  user=<utente> pass=$(rclone obscure '<TOKEN>') \
  --config /tmp/rclone-fototimeline.conf
scp /tmp/rclone-fototimeline.conf server2:fototimeline/rclone/rclone.conf
rm /tmp/rclone-fototimeline.conf
ssh server2 'chmod 600 ~/fototimeline/rclone/rclone.conf'
```

Il nome del remote (`lifetime`) deve essere quello di `RCLONE_REMOTO` nel
`.env`, con i due punti: `lifetime:`. Per un altro cloud basta un altro remote
di rclone (pCloud, Google Drive, S3, ...): l'app non cambia.

### 3. Keycloak: realm, client e amministratore

Il Keycloak è quello di presenze, sul server di produzione, con un **realm a
parte**: gli utenti delle foto non sono quelli di presenze. Dal server di
produzione:

```bash
cd ~/presenze && set -a && . ./.env.prod && set +a
KC="docker exec presenze-keycloak /opt/keycloak/bin/kcadm.sh"
DOMINIO=foto.marcocardinali.it

$KC config credentials --server http://localhost:8080/auth --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"

# Realm senza registrazione: gli utenti li crei tu.
$KC create realms -s realm=fototimeline -s enabled=true \
  -s registrationAllowed=false -s bruteForceProtected=true

# Client confidenziale: il login lo fa il backend (le foto si caricano con <img>,
# che non può mandare un token).
$KC create clients -r fototimeline -s clientId=fototimeline -s enabled=true \
  -s publicClient=false -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s "redirectUris=[\"https://$DOMINIO/login/oauth2/code/keycloak\"]" \
  -s "attributes={\"post.logout.redirect.uris\":\"https://$DOMINIO/\"}"

# Il ruolo per montare e smontare il cloud.
$KC create roles -r fototimeline -s name=fototimeline-admin

# Il tuo utente (password temporanea: Keycloak la fa cambiare al primo accesso).
$KC create users -r fototimeline -s username=marco -s enabled=true
$KC set-password -r fototimeline --username marco --new-password 'temporanea' --temporary
$KC add-roles -r fototimeline --uusername marco --rolename fototimeline-admin

# Il segreto del client, da mettere in KEYCLOAK_CLIENT_SECRET.
ID=$($KC get clients -r fototimeline -q clientId=fototimeline --fields id --format csv --noquotes)
$KC get clients/$ID/client-secret -r fototimeline
```

Altri utenti (i familiari): `create users` e `set-password` come sopra,
meglio con nome e email (`-s firstName=Anna -s lastName=Rossi -s email=...`):
il nome è quello che l'app mostra in "Caricata da". Senza `fototimeline-admin`
vedono e gestiscono le foto ma non montano né smontano, e dei telefoni vedono
solo il proprio. Il telefono si aggiunge poi nell'app (GUIDA.md, "Aggiungere
un familiare").
Chi riceve il ruolo deve uscire e rientrare: il ruolo entra nel token al login.

Se il deploy di presenze riconcilia i realm (`riconcilia-realm.py`), controlla
che lasci stare il realm `fototimeline`.

### 4. Il file `.env`

```bash
scp deploy/env.esempio server2:fototimeline/.env
ssh server2 'chmod 600 ~/fototimeline/.env && nano ~/fototimeline/.env'
```

Da compilare: dominio, password di Mongo e di rclone (stringhe lunghe a caso,
per esempio `openssl rand -base64 32`) e il segreto del client del passo 3.

### 5. Il runner

Se non c'è già (README, "CI su server2"): runner `server2-fototimeline` con
etichetta `fototimeline-build`. Il runner gira come `marco`, che deve essere nel
gruppo `docker` come per gli altri runner.

### 6. Il primo rilascio

Dalla scheda **Actions** del repository: workflow **Rilascio** → **Run
workflow**. Oppure portando `main` sul branch `produzione`:

```bash
git checkout -B produzione origin/main
git push origin produzione
```

Il workflow costruisce l'immagine, avvia i quattro container e aspetta che
l'app sia sana. Poi:

1. apri `https://<dominio>` ed entra con l'utente del passo 3;
2. in alto a destra: **Cloud smontato → Monta**;
3. **Importa cartella**: scegli la cartella di LifetimeCloud con le foto e
   **Importa e sposta**. Le foto finiscono in `FotoTimeline/AAAA/MM/GG/` e
   spariscono dalla cartella di origine (quelle già in archivio pure: il
   contenuto è identico).

Entrano anche le foto **HEIC** dell'iPhone e i **video** MP4/MOV (l'immagine
Docker ha `heif-convert` e `ffmpeg`). Un'importazione grande richiede tempo:
ogni foto passa da LifetimeCloud a server2 e torna indietro. Gira in sottofondo, una alla volta: la barra in basso
a destra mostra l'avanzamento, si può chiudere la finestra e si può annullare.

### Importazione automatica dal telefono

Con `FOTOTIMELINE_CARTELLA_AUTOMATICA` nel `.env` (per esempio
`/cloud/Da importare`), ogni `FOTOTIMELINE_INTERVALLO_AUTOMATICO` (15 minuti)
l'app guarda in quella cartella di LifetimeCloud e, se ci sono foto o video
(anche HEIC), li sposta nell'archivio, con le sottocartelle come album. Solo quando il cloud è montato.
Basta far caricare all'app di LifetimeCloud sul telefono le foto in quella
cartella.

### Foto nuove dai telefoni (pCloud)

Ogni familiare ha il suo telefono, che carica le foto su **pCloud** (cartella
"Automatic Upload", con una sottocartella per dispositivo), sul proprio account
o su quello di famiglia. L'app ha un **elenco di telefoni** (in alto a destra,
**Telefoni**): ognuno ha un nome ("Telefono di Anna"), un **proprietario** (lo
username Keycloak di chi ce l'ha), la sua cartella (`pcloud:Automatic Upload`,
o `pcloud-anna:Automatic Upload` per un altro account) e i suoi orari. Le foto
finiscono tutte nella cartella automatica qui sopra (sul server
`/cloud/telefono`, cioè `lifetime:telefono`):

1. ogni `N` ore (predefinito 6, per telefono) copia dalla sua cartella i file
   **nuovi** (foto, HEIC, video; niente PDF né file col punto) nella cartella
   automatica, senza sottocartelle: `Pixel 8/IMG_1.jpg` diventa
   `Pixel 8 - IMG_1.jpg`, così il nome del dispositivo non diventa un album. Un
   nome già presente (anche di un altro telefono) prende ` (2)`. Ogni copia si
   controlla (stessa dimensione) e si segna in MongoDB (`copie_telefono`, con
   la cartella, il telefono e il proprietario): un file già copiato non si
   ricopia più;
2. l'importazione automatica (ogni 15 minuti, col cloud montato) li sposta
   nell'archivio; per ogni file cerca nel registro la copia con quella
   destinazione e mette nella foto **"caricata da"** il proprietario del
   telefono. Per questo la cartella resta piatta: una sottocartella per
   telefono diventerebbe un album;
3. dopo `N` giorni dalla copia (predefinito 7; 0 = mai) li toglie dalla sua
   cartella, **solo se** non sono più nella cartella automatica, cioè se
   l'importazione li ha presi. Quelli rimasti lì (importazione non riuscita)
   restano anche su pCloud.

I telefoni girano uno alla volta: se due sono in ritardo, il secondo aspetta
"in coda". La copia passa dall'API di rclone (`operations/list`, `copyfile`,
`stat`, `deletefile`), non dal montaggio: funziona anche col cloud smontato, e
le foto aspettano nella cartella automatica finché non lo si monta.

**Il remote `pcloud`**, una volta sola, su server2:

```bash
docker run --rm -it -v ~/fototimeline/rclone:/config/rclone rclone/rclone:1.68 config
#  n (nuovo remote) → nome: pcloud → tipo: pcloud
#  client_id e client_secret: vuoti → advanced config: n
#  auto config: n   ← server2 non ha un browser
#  sul PC: rclone authorize "pcloud"   → login nel browser, poi copia il risultato
#  incolla il risultato in server2 → y (conferma) → q (esci)
docker restart fototimeline-rclone-1
```

Per un familiare con **un altro account pCloud** serve un altro remote, con un
altro nome (`pcloud-anna`), fatto allo stesso modo col suo login: i passi sono
in [GUIDA.md](GUIDA.md), "Aggiungere un familiare".

Il riavvio di rclone smonta il cloud: rimontalo dall'app (**Monta**). Poi,
da admin, **Telefoni** → **Aggiungi** (o **Modifica**): nome, proprietario
(si sceglie tra gli utenti già entrati o si scrive lo username), cartella,
**Attivo**, **Aggiungi**/**Salva**. **Sincronizza ora** fa subito un giro di
quel telefono; nell'elenco c'è com'è andato l'ultimo (copiati, già copiati,
tolti da pCloud, errori), i contatori di quello in corso e quando parte il
prossimo. **Elimina** toglie il telefono ma non il suo registro: rimesso con la
stessa cartella, non ricopia niente. Telefoni e stato stanno in MongoDB
(`sorgenti_telefono`).

Chi non è admin vede in **Telefoni** solo i telefoni di cui è proprietario e
può lanciarne un giro, ma non cambiarli né aggiungerne.

**Dal telefono unico all'elenco.** Prima la sincronizzazione era una sola
(`impostazioni/sincronizzazione-telefono`). All'avvio quel documento diventa il
primo telefono, "Telefono" (id `telefono`), con le stesse impostazioni e
l'ultimo giro, e poi si cancella; il registro resta com'è (la chiave è la
cartella) e le sue voci prendono l'id del telefono: niente si ricopia. Il
proprietario è `FOTOTIMELINE_TELEFONO_PROPRIETARIO` nel `.env`, altrimenti il
primo amministratore già entrato, altrimenti il primo amministratore che entra
dopo il rilascio.

Si attiva solo sul server (serve rclone) e con `FOTOTIMELINE_CARTELLA_AUTOMATICA`
dentro `RCLONE_PUNTO_MONTAGGIO`: altrimenti la finestra dice perché.

### Chi ha caricato cosa

Ogni foto nuova ha `caricataDa`, lo username Keycloak di chi l'ha portata:
chi l'ha caricata dal browser o ha lanciato "Importa cartella", e per la
cartella automatica il proprietario del telefono (i file messi lì a mano
restano senza). Le foto di prima restano senza ("—" nel visore). Gli utenti si
registrano da soli in MongoDB (`utenti`) quando entrano: username, nome, email,
ultimo accesso, admin. Non serve l'API admin di Keycloak.

### Foto già ordinate nel cloud

Le foto caricate su LifetimeCloud da fuori (per esempio con uno script),
direttamente in `FotoTimeline/AAAA/MM/GG/`, l'app non le conosce finché non
le si indicizza: in alto a destra, da admin e col cloud montato, **Indicizza
archivio** (`POST /api/archivio/indicizza`). Percorre tutto l'archivio,
saltando le cartelle col punto (`.backup`, `.miniature`), e a ogni file senza
scheda dà la scheda e la miniatura **senza copiarlo**: resta dov'è. Gira in
sottofondo come un'importazione (stessa barra, si può annullare, mai insieme a
un'importazione); alla fine la timeline si ricarica.

- La data viene dall'EXIF; se manca, dalla cartella `AAAA/MM/GG` in cui sta il
  file (origine "cartella"), altrimenti dalla data di modifica del file.
- Se la data dice un altro giorno, il file si sposta nella cartella giusta.
- I file che hanno già una scheda si saltano senza scaricarli: rifarla dopo
  ogni caricamento costa poco. Una copia identica di una foto che sta già in
  un'altra cartella non diventa una seconda scheda (il file resta lì).
- I file illeggibili finiscono tra gli errori della barra, senza fermare il resto.

#### Da pCloud, in quattro passi

`deploy/foto-da-pcloud.sh` gira sul PC (WSL), legge pCloud Drive da P: e lavora
in fasi separate, ognuna da rilanciare finché non finisce bene:

| Fase | Cosa fa |
|---|---|
| `anteprima` | non copia niente: quante foto, video e zip ci sono in ogni cartella di `RADICE`, per scegliere cosa escludere (`ESCLUDI`). |
| `scarica` | con rclone (remote `pcloud:`), in parallelo: foto, video e zip di `RADICE` in `~/foto-da-pcloud/specchio`, esclusioni comprese; poi `raccogli` legge da lì invece che da P:. |
| `raccogli` | cerca foto e video sotto `RADICE`, in tutte le sottocartelle e dentro gli zip, e li mette in `~/foto-da-pcloud/ordinate/AAAA/MM/GG` (EXIF, poi data del video, poi data del file). Le copie identiche entrano una volta. |
| `carica` | copia `ordinate/` in `lifetime:FotoTimeline` e controlla che ci sia tutto. Poi nell'app: **Indicizza archivio**. |
| `archivia` | su pCloud, in `ARCHIVIO/AAAA/AAAA-MM.zip`: uno zip per mese, controllato dopo la copia. |
| `pulisci` | solo dopo `GIORNI` (7) giorni da carica e archivia, e dopo aver scritto `CANCELLA`: toglie da pCloud gli originali copiati e le cartelle rimaste vuote. |

Per lo script una scansione in JPG è una foto: le cartelle di documenti vanno
escluse con `ESCLUDI` (relative a `RADICE`, separate da `:`). Si saltano sempre
l'archivio, `Crypto Folder` e le cartelle di sistema. Pulisci toglie solo le
cartelle svuotate da lui, mai le radici né, con tutto P: come radice, le
cartelle in cima (Automatic Upload, My Pictures, ...).

Gli zip con dentro anche altro (documenti, ...) e i file non letti restano su
pCloud, elencati in `~/foto-da-pcloud/da-controllare.txt`. `stato` dice a che
punto è. Sul PC serve spazio quanto tutte le foto, finché non si pulisce.

```bash
sudo apt install -y unzip zip libimage-exiftool-perl rclone   # rclone con il remote lifetime:
sudo mount -t drvfs P: /mnt/p
RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' deploy/foto-da-pcloud.sh anteprima
ESCLUDI='TASSE:banche:avvocato' deploy/foto-da-pcloud.sh anteprima   # finché torna
deploy/foto-da-pcloud.sh raccogli
deploy/foto-da-pcloud.sh carica
deploy/foto-da-pcloud.sh archivia
deploy/foto-da-pcloud.sh pulisci      # dopo 7 giorni
```

#### Da Amazon Foto o da una cartella del PC

Amazon Foto non ha API pubbliche: la libreria si scarica con l'app per Windows
e `deploy/foto-da-cartella.sh` (fasi `anteprima`, `raccogli`, `carica`,
`archivia` facoltativa, `stato`) la porta in `lifetime:FotoTimeline` da
`SORGENTE`, lavorando in `~/foto-da-amazon` (`LAVORO`). Senza EXIF la data
viene dal nome del file prima che dalla data del file, ` (1)` nel nome è un
doppione del download, e le impronte di `~/foto-da-pcloud` (`ALTRI_LAVORI`)
evitano doppioni e nomi già presi. Non cancella niente. Passo per passo:
GUIDA.md, "Da Amazon Foto". Le parti comuni dei due script sono in
`deploy/lib-foto.sh`; `deploy/test-foto.sh` li prova su file finti e
confronta `foto-da-pcloud.sh` con la versione di prima di `lib-foto.sh`.

I file caricati dal sito o dalle app di LifetimeCloud sono cifrati nel browser
(cominciano con `LCB2`): via WebDAV arrivano cifrati e l'app non li legge. Le
foto devono arrivare via WebDAV (rclone, l'app stessa, FolderSync sul telefono).

### Luoghi dal GPS (GeoNames)

Il nome del posto (Sperlonga, Lazio · Italia) si ricava dalle coordinate sul
server, senza chiamare servizi esterni: nell'immagine ci sono i centri abitati
di [GeoNames](https://www.geonames.org/), che il Dockerfile scarica nello
stage `geonames` da `https://download.geonames.org/export/dump/` e mette in
`/app/geonames` (`FOTOTIMELINE_LUOGHI`):

| File | Cosa | Peso nell'immagine |
|---|---|---|
| `cities500.txt` | ~225 mila centri sopra i 500 abitanti (anche molte frazioni italiane), senza la colonna dei nomi alternativi | ~25 MB |
| `alternateNames-it.txt` | i nomi in italiano (Roma, Parigi, Baviera), filtrati da `alternateNamesV2.zip` (200 MB, scaricato e buttato nello stage) | ~0,9 MB |
| `admin1CodesASCII.txt`, `countryInfo.txt` | regioni e nazioni | ~0,2 MB |

In tutto circa **26 MB** in più nell'immagine. All'avvio l'app li legge in un
paio di secondi in un indice compatto (array di primitivi, k-d tree): circa
**10 MB di heap** sui 384 della JVM. Oltre 30 km dal centro abitato più vicino
(`FOTOTIMELINE_LUOGHI_DISTANZA`) il luogo resta sconosciuto: mare aperto.
Se la cartella manca o è vuota l'app parte lo stesso, senza luoghi (nel log:
"Luoghi spenti").

- Le foto nuove prendono il luogo all'importazione e all'indicizzazione.
- Per quelle già in archivio: da admin **Calcola luoghi** (`POST
  /api/archivio/luoghi`), in sottofondo con la barra delle importazioni. Legge
  solo MongoDB, non il cloud: va anche da smontato, 150 mila foto in meno di
  un minuto. Rilanciarlo non rifà quelle già calcolate (anche quelle in mare
  aperto, segnate con `luogoCalcolato`); se tutte ce l'hanno chiede se
  ricalcolarle tutte (`?tutte=true`), utile dopo un aggiornamento del dataset.
- **Aggiornare il dataset**: lo stage resta nella cache di Docker e non si
  riscarica a ogni rilascio (se la cache viene pulita, si riscarica da sé: ~215
  MB, un minuto). Per prendere quello nuovo, su server2 dal checkout del runner
  e con `C` come in "Comandi utili su server2": `$C build --no-cache app`, poi
  il rilascio e **Calcola luoghi** → ricalcola tutte.
- **Attribuzione**: i dati GeoNames sono sotto licenza
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): l'app lo dice nel
  pannello del filtro "Luogo" e nel visore, accanto alla posizione. Non
  toglierlo.
- Nelle grandi città straniere il più vicino può essere un quartiere che
  GeoNames tiene come centro a sé ("Paris 16 Passy", "City of Westminster"); i
  quartieri segnati come tali (PPLX, per esempio Trastevere) si saltano.

### Video compatibili (HEVC → H.264)

I video HEVC dell'iPhone (e VP9, ProRes, H.264 a 10 bit, audio PCM) non si
vedono in tutti i browser: Firefox e Chrome su Linux e su molti Windows senza
accelerazione hardware non li riproducono. L'app ne fa una **versione
compatibile** H.264/AAC in MP4 (`-preset veryfast -crf 23 -movflags +faststart`,
lato corto al massimo 1080, rotazione del telefono applicata) e il browser
riceve quella; l'originale resta com'è e si scarica dal visore.

- I video nuovi entrano in coda all'importazione, se ffprobe dice che servono;
  per quelli già in archivio, da admin e col cloud montato, **Converti video**
  (`POST /api/archivio/video/converti`). Quelli mai analizzati la coda li guarda
  prima con ffprobe e li converte solo se serve. Un H.264 col `moov` in fondo
  (senza faststart) non si converte: il browser lo legge a pezzi con `Range`.
- **Un video alla volta**, su un thread solo, con `nice -n 19` e al massimo
  `FOTOTIMELINE_VIDEO_THREAD` thread di ffmpeg (2 di predefinito). Lo stato sta
  in MongoDB, sulla scheda (`conversione`: `IN_CODA`, `IN_CORSO`, `FATTA`,
  `ERRORE`): dopo un rilascio la coda riparte da sola, dal video che era a metà.
  Un video che non riesce finisce in `ERRORE` col motivo e la coda va avanti;
  "Converti video" ci riprova. Col cloud smontato la coda **aspetta** (i video
  restano in coda) e riparte entro un minuto dal montaggio.
- **Dove**: `FotoTimeline/.compatibili/` nel cloud, accanto agli originali, non
  sul disco di server2 come le miniature e le viste JPEG degli HEIC. Una
  miniatura pesa 30 KB, un video convertito decine di MB: tutti insieme
  riempirebbero il disco del VPS. Nel cloud lo spazio c'è, e il video si
  legge da lì esattamente come l'originale (stesso montaggio, stessa cache di
  rclone). Si può cambiare con `FOTOTIMELINE_VIDEO_DESTINAZIONE` (una cartella
  fuori dall'archivio, per esempio un volume locale, non dipende dal cloud).
  "Indicizza archivio" salta la cartella perché comincia col punto.
- **Disco locale**: ffmpeg scrive il convertito in `/tmp` del container, poi lo
  copia nel cloud; l'originale passa dalla cache di rclone (massimo 5 GB). Per
  un video di 10 minuti servono qualche centinaio di MB liberi su server2.

**Quanto costa** (stime per minuto di video, su un VPS a 4 vCPU con 2 thread;
provato: 10 s di HEVC 4K30 → 1080p in 32 s):

| Originale | CPU per minuto di video | Convertito, per minuto |
|---|---|---|
| HEVC 1080p30 (iPhone "HD") | ~1 minuto | 40–60 MB |
| HEVC 4K30 | ~3 minuti | 40–60 MB (l'originale ~170 MB) |
| HEVC 4K60 | ~6 minuti | 50–80 MB (l'originale ~400 MB) |

Mentre converte prende `FOTOTIMELINE_VIDEO_THREAD` core (a priorità bassa:
build e app hanno la precedenza). Per un trasloco grande lo si può alzare nel
`.env` e rifare il rilascio; per lasciare più CPU ai runner, `1` (circa il
doppio del tempo). `FOTOTIMELINE_VIDEO_ATTIVA=false` le spegne del tutto
(i video si servono come sono); `FOTOTIMELINE_VIDEO_RISOLUZIONE=720` le fa più
leggere e più veloci.

### Google Foto (Takeout e Picker)

I passi per l'utente sono in [GUIDA.md](GUIDA.md), sezione 6. Qui quello che
serve sul server.

**Importa da Google Takeout** (admin, pulsante *Google Foto*):

- Takeout consegna gli zip su Google Drive (cartella `Takeout`). Serve un
  remote di rclone di tipo `drive`, nome a scelta (predefinito `gdrive`),
  configurato sul PC col browser e copiato in `~/fototimeline/rclone/rclone.conf`
  come quello di pCloud. Il `rclone.conf` è montato in sola lettura: rclone
  rinnova l'access token di Drive in memoria a ogni avvio (il refresh token nel
  file resta valido), e nel log può dire che non riesce a salvarlo: è normale.
  Con client_id vuoto usa quello di rclone; con un client tuo in stato "Test"
  il token scadrebbe dopo 7 giorni.
- Gli zip si scaricano **uno alla volta** con `operations/copyfile` asincrono
  in `$FOTOTIMELINE_DATI/takeout`, montata come `/takeout` sia in rclone (che
  scrive) sia nell'app (che legge, estrae un file alla volta accanto allo zip
  e poi cancella). La cartella deve essere di `marco` (uid 1000): la crea il
  workflow di rilascio; a mano `mkdir -p ~/fototimeline/takeout`. Prima di ogni
  zip l'app controlla lo spazio libero lì: zip + `FOTOTIMELINE_TAKEOUT_RISERVA`
  (2 GB); se manca si ferma con un messaggio e non scarica niente. Le foto poi
  vanno nel cloud passando dalla cache del VFS di rclone (al massimo 5 GB, sul
  disco di server2).
- Registro in MongoDB, `takeout_zip` (nome + dimensione + data dello zip su
  Drive, stato, contatori, fin dove è arrivato); impostazioni in
  `impostazioni/google-takeout`. Uno zip a metà riparte dal file a cui era
  arrivato, anche dopo un riavvio (se l'importazione era in corso riparte da
  sola); se lo zip scaricato è ancora in `/takeout` non si riscarica.
- I metadati di Google (data, GPS, descrizione, preferita, album) valgono solo
  dove la foto non li ha già, e stanno solo nella scheda in MongoDB: i file non
  si riscrivono (nell'immagine non c'è exiftool). Il backup dei metadati li
  contiene.
- **Cosa Takeout non porta**: le persone/volti riconosciuti, i commenti e le
  foto degli album condivisi di altri (dipende da Google), la cartella
  bloccata; le modifiche fatte in Google Foto arrivano come file a parte
  (`-edited`/`-modificato`), che entra come foto in più; le foto in movimento e
  le Live Photo arrivano come foto + video separati, e così entrano. I formati
  che l'archivio non accetta (`.avi`, `.3gp`, `.mkv`, RAW...) si contano come
  "saltati" e restano nello zip.
- Con *Togli gli zip da Drive dopo N giorni* > 0, ogni 6 ore (e alla fine di
  ogni giro) gli zip importati **senza errori** da più di N giorni si cancellano
  da Drive, solo se lì c'è ancora lo stesso file (stessa dimensione). Serve lo
  scope `drive` completo nel remote.

**Scegli da Google Foto** (chiunque sia entrato):

- Un progetto Google Cloud con la **Google Photos Picker API** abilitata, un
  client OAuth **Applicazione web** col redirect
  `https://<dominio>/api/google/callback` (GUIDA.md, 6.4). Nel `.env`:
  `FOTOTIMELINE_GOOGLE_CLIENT_ID`, `FOTOTIMELINE_GOOGLE_CLIENT_SECRET` (vuoti =
  il pulsante non c'è per chi non è admin) e `FOTOTIMELINE_GOOGLE_CHIAVE`
  (`openssl rand -base64 32`). Il secret non esce mai: non va nei log, nelle
  risposte, né nell'indirizzo del consenso.
- I refresh token stanno in `google_token`, uno per utente, cifrati con
  AES-256-GCM (chiave = SHA-256 di `FOTOTIMELINE_GOOGLE_CHIAVE`, o del client
  secret se la chiave è vuota). Se la chiave cambia i token salvati non si
  leggono più e l'app chiede a ognuno di ricollegare Google.
- Limiti di Google: fino a 2000 foto per scelta; gli indirizzi di download
  valgono 60 minuti; la Picker API ha quote per progetto (richieste al minuto)
  ampiamente sopra l'uso di una famiglia. Con l'app OAuth in stato "Test" il
  collegamento scade dopo 7 giorni (fino a 100 utenti di prova).
- Le foto scaricate (`=d`, con l'EXIF; i video `=dv`) entrano come *Carica
  foto*: doppioni saltati, "caricate da" chi sceglie, `createTime` di Google come
  data se l'EXIF non c'è. Serve il cloud montato.

## Salute e avvisi su Telegram

Il bottone **Salute** in alto a destra (solo admin) mostra con un pallino
verde, giallo o rosso: rclone, cloud montato, ultimo backup dei metadati,
sincronizzazione dei telefoni, importazioni (e file in attesa nella cartella
automatica), spazio sul disco delle miniature, su `lifetime:` e sui remote dei
telefoni (`pcloud:`, ...),
numeri dell'archivio. Il pallino sul bottone è lo stato peggiore; si aggiorna
ogni 5 minuti. `/salute` resta l'healthcheck di Docker e non cambia.

Soglie: backup più vecchio di due intervalli (con `P1D`, due giorni) rosso,
mai fatto giallo; i telefoni sono una voce sola, rossa se un telefono attivo
è fermo da più di due dei suoi intervalli o ha l'ultimo giro in errore, col
nome del telefono nel messaggio (gli errori del token di pCloud, per esempio
`2094`, lo dicono); spazio libero sotto il 10% giallo, sotto il 5% rosso;
cloud smontato giallo, rclone che non risponde rosso.

Ogni 15 minuti l'app ricontrolla e scrive su Telegram **solo quando una voce
cambia**: diventa rossa, o torna a posto ("risolto"). Il giallo non avvisa,
salvo `FOTOTIMELINE_TELEGRAM_ATTENZIONE=true`. Gli stati visti stanno in
Mongo: un riavvio o un rilascio non rimandano niente.

Per accenderli, una volta:

1. su Telegram scrivi a **@BotFather**: `/newbot`, un nome, un nome utente che
   finisce con `bot`. Ti risponde con il **token** (`123456789:AA...`);
2. apri la chat con il tuo bot e scrivigli qualcosa (un bot non può scrivere
   per primo);
3. prendi il **chat id**: apri
   `https://api.telegram.org/bot<TOKEN>/getUpdates` e cerca `"chat":{"id":...}`
   (un numero; per un gruppo è negativo e il bot deve essere nel gruppo);
4. mettili nel `.env` di server2 (`nano ~/fototimeline/.env`):

   ```bash
   FOTOTIMELINE_TELEGRAM_TOKEN=123456789:AA...
   FOTOTIMELINE_TELEGRAM_CHAT=987654321
   ```

5. rilascia (workflow **Rilascio**, come sopra): le variabili entrano nel
   container solo così;
6. **Salute → Messaggio di prova**: deve arrivare su Telegram. Se no, il
   messaggio d'errore dice cosa risponde Telegram (`chat not found`: chat id
   sbagliato o non hai scritto al bot; `Unauthorized`: token sbagliato).

Il token non finisce nei log né nelle risposte dell'app. Il link nei messaggi
è `https://$FOTOTIMELINE_DOMINIO`.

### Ricordi su Telegram ("Accadde oggi")

Con token e chat impostati, ogni mattina dalle 8 (ora italiana) lo stesso bot
manda le foto dello stesso giorno negli anni passati: un album di al massimo 6
foto (una per anno prima, preferite prima, niente video né quasi doppioni),
la didascalia col luogo e il link `https://$FOTOTIMELINE_DOMINIO/?ricordi=oggi`
(dietro login, come il resto: nessun link pubblico). Le immagini sono la vista
ridotta senza EXIF dei link di condivisione (fatta al momento se il cloud è
montato) o, col cloud smontato, la miniatura: mai l'originale né il GPS.

Il giorno fatto sta in Mongo (`impostazioni/ricordi-telegram`): mai due invii
lo stesso giorno, anche con un rilascio in mezzo; se alle 8 l'app era giù,
manda al primo controllo dopo (ogni 5 minuti), finché è lo stesso giorno. Nei
giorni senza ricordi niente messaggio. Se Telegram non risponde riprova al
controllo dopo.

Si regolano dall'app, **Salute → Ricordi su Telegram** (acceso, ora, numero di
foto, "Nessun ricordo oggi", **Manda ora una prova**); le variabili del `.env`
sono solo i valori di partenza, finché non si salva dall'app:

```bash
FOTOTIMELINE_RICORDI_ATTIVO=true
FOTOTIMELINE_RICORDI_ORA=08:00
FOTOTIMELINE_RICORDI_FOTO=6
# Una chat (o un gruppo, col bot dentro) solo per i ricordi; vuota = quella degli avvisi.
FOTOTIMELINE_RICORDI_CHAT=
FOTOTIMELINE_RICORDI_NESSUNO=false
```

Il fuso è `FOTOTIMELINE_RICORDI_FUSO` (`Europe/Rome`).

## Comandi utili su server2

```bash
cd ~/actions-runner-fototimeline/_work/fototimeline/fototimeline   # checkout del runner
C="docker compose -p fototimeline -f deploy/docker-compose.yml --env-file $HOME/fototimeline/.env"
$C ps
$C logs -f app
$C logs -f rclone
mountpoint ~/fototimeline/cloud        # montato o no, visto dall'host
```

Se rclone viene fermato male, il montaggio può restare appeso
(`Transport endpoint is not connected`): `fusermount3 -uz ~/fototimeline/cloud`.
Il workflow di rilascio lo fa da solo.

## Backup

- Gli **originali** sono su LifetimeCloud: dal sito o dall'app ogni file
  sovrascritto resta come versione per un anno; via WebDAV le ultime 3 versioni
  per 30 giorni.
- I **metadati** (date corrette a mano, titoli, tag, album) stanno in MongoDB
  su server2, e l'app ne fa una copia **da sola** nel cloud, accanto alle foto:
  `FotoTimeline/.backup/fototimeline-AAAA-MM-GG-HHmmss.json.gz`. Una al giorno
  (`FOTOTIMELINE_BACKUP_INTERVALLO`), le ultime 30 (`FOTOTIMELINE_BACKUP_TENERE`).
  Serve il cloud montato: se di notte è smontato, la copia si fa entro un'ora
  da quando lo si monta. Gli admin la possono fare subito con **Backup** in
  alto a destra.
- Le **miniature** non servono nel backup: l'app le rifà dagli originali.

### Ripristino dei metadati

Su server2, con il cloud montato. Il file è un documento per riga in Extended
JSON, che `mongoimport` rimette così com'è (`upsert`: le foto già presenti si
sovrascrivono con la versione del backup, le altre restano):

```bash
cd ~/fototimeline
B=$(ls -1 cloud/FotoTimeline/.backup/fototimeline-*.json.gz | tail -1); echo "$B"
gunzip -c "$B" | docker exec -i fototimeline-mongo-1 sh -c \
  'mongoimport --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
   --authenticationDatabase admin --db fototimeline --collection foto --mode upsert'
```

Per ripartire da zero (per esempio su un server nuovo) prima si svuota la
collezione: `docker exec fototimeline-mongo-1 mongosh -u ... -p ... --authenticationDatabase admin fototimeline --eval 'db.foto.drop()'`.
