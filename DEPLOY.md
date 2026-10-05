# Rilascio su server2

FotoTimeline gira su **server2** (Contabo, `ssh server2`), la stessa macchina
dei runner di build di presenze e TrovaCampo. Le foto **non** stanno sul disco
di server2: stanno su **LifetimeCloud** (o un altro cloud supportato da
rclone), che un container rclone monta quando un amministratore lo chiede
dall'app.

```
internet ─▶ caddy (HTTPS, Let's Encrypt) ─▶ app (Spring Boot + Angular) ─┬─▶ mongo
                                                                          ├─▶ rclone rcd ─WebDAV─▶ LifetimeCloud
                                                                          └─▶ visione (CLIP, ricerca per contenuto)
login: Keycloak di presenze, realm "fototimeline"
```

| Cosa | Dove |
|---|---|
| Originali | LifetimeCloud, cartella `FotoTimeline/AAAA/MM/GG/` |
| Foto da importare | qualunque cartella di LifetimeCloud (l'app vede la radice come `/cloud`) |
| Miniature | volume Docker `fototimeline_miniature` su server2 (si rigenerano dagli originali) |
| Metadati (date, tag, album) | MongoDB, volume `fototimeline_mongo` su server2 |
| Indice della ricerca per contenuto | volume `fototimeline_visione-dati` su server2 (si rifà dalle miniature) |
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

Altri utenti: `create users` e `set-password` come sopra; senza
`fototimeline-admin` vedono e gestiscono le foto ma non montano né smontano.
Chi riceve il ruolo deve uscire e rientrare: il ruolo entra nel token al login.

Se il deploy di presenze riconcilia i realm (`riconcilia-realm.py`), controlla
che lasci stare il realm `fototimeline`.

### 4. Il file `.env`

```bash
scp deploy/env.esempio server2:fototimeline/.env
ssh server2 'chmod 600 ~/fototimeline/.env && nano ~/fototimeline/.env'
```

Da compilare: dominio, password di Mongo e di rclone (stringhe lunghe a caso,
per esempio `openssl rand -base64 32`), il segreto del client del passo 3 e
`VISIONE_SEGRETO` (`openssl rand -hex 32`), che l'app e il servizio visione
si scambiano anche se stanno solo sulla rete interna.

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

Il workflow costruisce le immagini (app e visione), avvia i cinque container e
aspetta che l'app sia sana. La prima volta l'immagine di visione scarica torch
e i pesi di CLIP: qualche minuto in più. Poi:

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

### Foto nuove dal telefono (pCloud)

Il telefono carica le foto su **pCloud** (cartella "Automatic Upload", con una
sottocartella per dispositivo). L'app le porta da sola nella cartella
automatica qui sopra (sul server `/cloud/telefono`, cioè `lifetime:telefono`):

1. ogni `N` ore (da **Telefono**, predefinito 6) copia da pCloud i file
   **nuovi** (foto, HEIC, video; niente PDF né file col punto) nella cartella
   automatica, senza sottocartelle: `Pixel 8/IMG_1.jpg` diventa
   `Pixel 8 - IMG_1.jpg`, così il nome del dispositivo non diventa un album. Un
   nome già presente prende ` (2)`. Ogni copia si controlla (stessa
   dimensione) e si segna in MongoDB (`copie_telefono`): un file già copiato
   non si ricopia più;
2. l'importazione automatica (ogni 15 minuti, col cloud montato) li sposta
   nell'archivio;
3. dopo `N` giorni dalla copia (predefinito 7; 0 = mai) li toglie da pCloud,
   **solo se** non sono più nella cartella automatica, cioè se l'importazione
   li ha presi. Quelli rimasti lì (importazione non riuscita) restano anche su
   pCloud.

La copia passa dall'API di rclone (`operations/list`, `copyfile`, `stat`,
`deletefile`), non dal montaggio: funziona anche col cloud smontato, e le foto
aspettano nella cartella automatica finché non lo si monta.

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

Il riavvio di rclone smonta il cloud: rimontalo dall'app (**Monta**). Poi,
sempre da admin, **Telefono** in alto a destra: controlla la cartella su pCloud
(`pcloud:Automatic Upload`), **Attiva** e **Salva**. **Sincronizza ora** fa
subito un giro; sotto c'è com'è andato l'ultimo (copiati, già copiati, tolti da
pCloud, errori) e quando parte il prossimo. Impostazioni e stato stanno in
MongoDB (`impostazioni`, documento `sincronizzazione-telefono`).

Si attiva solo sul server (serve rclone) e con `FOTOTIMELINE_CARTELLA_AUTOMATICA`
dentro `RCLONE_PUNTO_MONTAGGIO`: altrimenti la finestra dice perché.

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

### Ricerca per contenuto (visione)

Con l'interruttore **Per contenuto** accanto al campo di ricerca si scrive cosa
c'è nella foto, in italiano ("spiaggia", "cane", "torta di compleanno",
"neve") e le foto arrivano dalla più somigliante, senza bisogno di tag. Gli
altri filtri (tag, album, preferite) valgono anche qui; la mappa e la colonna
dei mesi no (i risultati non sono in ordine di data).

Lo fa il container `visione` (cartella `visione/`, Python con FastAPI), sulla
rete interna e non esposto, con il segreto `VISIONE_SEGRETO`:

- **il modello**: CLIP ViT-B/32 per le immagini (`openai/clip-vit-base-patch32`,
  solo la parte visiva) e, per il testo,
  `sentence-transformers/clip-ViT-B-32-multilingual-v1`, una DistilBERT
  multilingue addestrata a dare per una frase italiana lo stesso vettore del
  CLIP inglese. Scelto perché è il più leggero che capisce l'italiano: circa
  220 milioni di parametri in tutto, contro i 370 di un CLIP con XLM-RoBERTa
  (open_clip `xlm-roberta-base-ViT-B-32`) e il doppio dei modelli "large";
  su CPU è quello che sta in 0,5 GB di RAM e fa una foto in 0,1–0,2 secondi.
  La qualità è da CLIP B/32: le cose (animali, cibo, mezzi, paesaggi, neve,
  mare) le trova bene, i dettagli fini e le scritte no. Il testo si cerca come
  "una foto di …", che con CLIP funziona meglio della parola sola;
- **i pesi** (~1,1 GB) entrano nell'immagine quando la si costruisce: a
  runtime niente download;
- **l'indice**: un vettore di 512 numeri (float16) per foto, in memoria nel
  servizio e su disco nel volume `visione-dati` (`indice.npz` più un giornale
  delle modifiche, scritto a ogni foto: un riavvio non perde niente). Nella
  JVM dell'app (384 MB) non ci starebbe; l'app tiene solo, su ogni foto,
  quando è entrata nell'indice (`contenutoIndicizzato`).

Come entrano le foto:

1. ogni foto nuova (caricamento, importazione, "Indicizza archivio") manda la
   sua **miniatura**, già sul disco di server2, a visione, in sottofondo:
   l'importazione non aspetta, e il cloud non serve;
2. per quelle di prima, da admin: **Indicizza contenuto** in alto a destra (tra
   parentesi quante ne mancano). Le manda a blocchi di 16, con l'avanzamento e
   **Annulla**; se il server o l'app ripartono, riparte da solo da dove era
   arrivato (prende solo le foto senza indice). Se una miniatura manca la rifà
   dall'originale, e lì serve il cloud montato: con il cloud smontato quelle
   foto restano tra gli errori e le riprende il giro dopo;
3. una foto eliminata si toglie anche dall'indice.

Se l'indice di visione va perso (volume cancellato) o si cambia modello,
**Indicizza contenuto** con tutte le foto già indicizzate chiede se rifarlo
**da capo**.

**Quanto costa, con 150.000 foto:**

| | |
|---|---|
| RAM di visione | ~0,45 GB a riposo (modello caricato), picchi ~0,9 GB mentre calcola un blocco; più ~150 MB di indice. Limite nel compose: 1,5 GB (`mem_limit`) |
| CPU | 2 thread (`VISIONE_THREAD`), con `nice` e `cpu_shares` bassi: le build della CI passano avanti |
| Disco | immagine ~2,6 GB (torch CPU ~1,4 GB, pesi ~1,1 GB); volume `visione-dati` ~160 MB |
| Tempo di "Indicizza contenuto" | ~0,14 s a foto con 2 thread (misurato su 4 vCPU): 150.000 foto ≈ 6 ore; su server2, con le build in mezzo, anche 8–12. Si lascia andare di notte: riprende da solo |
| Una foto nuova | ~0,2 s, in sottofondo |
| Una ricerca | ~0,3 s (testo in vettore e confronto con tutto l'indice) |

Quanto essere severi si regola con `FOTOTIMELINE_VISIONE_SOGLIA` (predefinito
0,24: somiglianza minima, le foto giuste di solito stanno tra 0,25 e 0,33,
quelle a caso intorno a 0,20) e `FOTOTIMELINE_VISIONE_MARGINE` (0,06: si
tengono solo le foto entro questo distacco dalla più somigliante), nel
`environment` dell'app.

Sul PC, senza `VISIONE_URL`, la funzione non c'è e l'app va come prima. Per
provarla in locale:

```bash
docker build -t fototimeline-visione visione/
docker run -d -p 127.0.0.1:8000:8000 -e VISIONE_SEGRETO=prova -v visione-dati:/dati fototimeline-visione
VISIONE_URL=http://localhost:8000 VISIONE_SEGRETO=prova ./mvnw spring-boot:run   # in backend/
```

## Comandi utili su server2

```bash
cd ~/actions-runner-fototimeline/_work/fototimeline/fototimeline   # checkout del runner
C="docker compose -p fototimeline -f deploy/docker-compose.yml --env-file $HOME/fototimeline/.env"
$C ps
$C logs -f app
$C logs -f rclone
$C logs -f visione                     # ricerca per contenuto
docker stats --no-stream fototimeline-visione-1
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
