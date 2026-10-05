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

I file caricati dal sito o dalle app di LifetimeCloud sono cifrati nel browser
(cominciano con `LCB2`): via WebDAV arrivano cifrati e l'app non li legge. Le
foto devono arrivare via WebDAV (rclone, l'app stessa, FolderSync sul telefono).

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
