# FotoTimeline

Gestore di foto con timeline, da usare in locale sul proprio PC.

**Le foto da pCloud, passo per passo** (trasloco, telefono, problemi noti): [GUIDA.md](GUIDA.md).
Il server: [DEPLOY.md](DEPLOY.md).

- **Backend**: Spring Boot 3.5, Java 21, Maven, MongoDB (solo metadati).
- **Frontend**: Angular 20 standalone, zoneless, signals; nessuna libreria UI.
- **File**: gli originali stanno su disco in `~/FotoTimeline`, in una
  cartella per anno, mese e giorno di scatto, col loro nome:

  ```
  FotoTimeline/
  ├── 2024/
  │   └── 08/
  │       ├── 15/
  │       │   ├── IMG_0001.jpg
  │       │   └── IMG_0001 (2).jpg   ← stesso nome, foto diversa
  │       └── 16/
  │           └── DSC_4410.jpg
  └── .miniature/                  ← anteprime, si rigenerano
  ```

  Così l'archivio si sfoglia anche da Esplora risorse e si salva con un
  normale backup.

## Cosa fa

- **Timeline** per mese e per giorno, con scorrimento infinito e griglia
  "giustificata" che rispetta il formato di ogni foto.
- **Colonna degli anni/mesi** a destra con il numero di foto: un clic salta
  a quel mese, e si evidenzia il mese che si sta guardando.
- **Data di scatto dall'EXIF** (DateTimeOriginal); se manca, la data del file
  (importazione) o quella di caricamento. Si può correggere a mano.
- **Cartelle sempre in ordine**: se cambi la data di una foto, il file si
  sposta nella cartella del nuovo giorno; le cartelle rimaste vuote spariscono.
  All'avvio il server rimette nella cartella giusta ogni foto fuori posto
  (anche gli archivi delle versioni precedenti).
- Dall'EXIF anche **fotocamera, posizione GPS** (link a OpenStreetMap) e
  **orientamento**: le miniature escono già dritte.
- **Caricamento** col bottone o trascinando le foto nella finestra, con
  avanzamento. **Niente doppioni**: una foto con lo stesso contenuto
  (SHA-256) non entra due volte.
- **Importa cartella**: con un navigatore di cartelle si sceglie da dove
  importare (sottocartelle comprese); volendo, il nome della sottocartella
  diventa l'album, e con "sposta" gli originali vengono tolti dall'origine.
  Gira in sottofondo con la barra di avanzamento e si può annullare.
- **Indicizza archivio** (admin): le foto messe a mano o da uno script nelle
  cartelle `AAAA/MM/GG` dell'archivio entrano nella timeline senza essere
  copiate; data dall'EXIF o, se manca, dalla cartella. In sottofondo, con la
  stessa barra delle importazioni.
- **Importazione automatica**: una cartella (per esempio quella dove il
  telefono carica nel cloud) che ogni 15 minuti si svuota nell'archivio.
- **Foto dai telefoni via pCloud** (sul server): un elenco di telefoni, uno per
  familiare, ognuno con la sua cartella su pCloud (anche su account diversi),
  il suo proprietario e i suoi orari. Ogni tot ore copia le foto nuove nella
  cartella automatica e, dopo qualche giorno, le toglie da pCloud se sono
  state importate. L'admin li aggiunge e li cambia; ognuno vede lo stato del
  proprio e può lanciarne un giro (DEPLOY.md).
- **Archivio di famiglia**: tutti vedono tutte le foto, ma ogni foto sa chi
  l'ha portata ("Caricata da Anna" nel visore): chi l'ha caricata o importata,
  o il proprietario del telefono da cui arriva. Le foto di prima restano senza.
- **Visore** a schermo intero: ← → per scorrere, `F` preferita, `I` pannello
  informazioni, `Esc` chiude. Dal pannello si modificano titolo, descrizione,
  tag, album, data; si scarica l'originale o si elimina.
- **Mappa**: le foto con posizione GPS su OpenStreetMap, raggruppate quando
  sono vicine; dal popup si apre la foto. Rispetta i filtri.
- **Luoghi**: dal GPS il nome del posto (comune o località, regione, nazione,
  in italiano: "Sperlonga, Lazio · Italia", "Parigi, Île-de-France · Francia"),
  calcolato sul server senza servizi esterni con i dati di
  [GeoNames](https://www.geonames.org/) (CC BY 4.0). Si vede nel visore e nel
  popup della mappa; il filtro **📍 Luogo** sceglie nazione, regione o luogo
  da un elenco con i conteggi, o li cerca per nome. Le foto nuove lo prendono
  all'importazione; per quelle già in archivio c'è **Calcola luoghi** (admin),
  in sottofondo con la barra. Oltre 30 km dal centro abitato più vicino (mare
  aperto) il luogo resta sconosciuto.
- **Accadde oggi**: in cima alla timeline le foto dello stesso giorno negli
  anni passati ("3 anni fa"); si chiude fino al giorno dopo.
- **App sul telefono**: dal browser "Aggiungi a schermata Home" / "Installa
  app" (manifest, icone, service worker); si apre a schermo intero.
- **Backup dei metadati**: ogni giorno una copia di date, titoli, tag e album
  nell'archivio (`.backup/`), ripristinabile con `mongoimport`.
- **Salute** (admin): rclone, cloud, backup, telefoni, importazioni, spazio e
  numeri dell'archivio con un pallino verde/giallo/rosso; avvisi su Telegram
  quando una voce cambia stato (DEPLOY.md).
- **Ricerca e filtri**: testo libero (titolo, descrizione, file, tag, album,
  fotocamera, luogo), tag, album, luogo, solo preferite, "Caricate da" (chi le
  ha portate, con quante foto).
- **Foto quasi uguali**: raffiche, scatti ripetuti, la stessa foto ridimensionata o
  ricompressa da WhatsApp. Un gruppo alla volta con le miniature affiancate e,
  sotto, risoluzione, peso, data e fotocamera; suggerisce quale tenere (la
  risoluzione più alta, poi il file più grande, poi chi ha l'EXIF, poi la più
  vecchia; le preferite restano sempre). "Togli le altre" elimina le scartate,
  "Non sono doppioni" non le ripropone più. Il confronto usa un'impronta
  percettiva (pHash a 64 bit) calcolata dalla miniatura all'importazione; per le
  foto già in archivio c'è **Calcola impronte** (admin), in sottofondo.
- **Selezione multipla** (bottone *Seleziona* o Ctrl+clic): aggiungi/togli
  tag, sposta in un album, segna preferite, elimina; "seleziona giorno" per
  prendere un giorno intero.
- **Condividere con un link** (chi non ha un account, per esempio gli amici
  dopo una festa): dalla selezione (*Condividi…*) o da un album aperto
  (*Condividi album*) si crea un link `https://<dominio>/c/<token>` con
  titolo, scadenza (1, 7, 30 giorni o mai), download degli originali sì/no e
  posizione GPS sì/no. Chi lo apre vede una galleria di sola lettura, senza
  menu né login. *Condivisioni* elenca i link (visite, scadenza) con *Copia
  link* e *Revoca*. Dettagli sotto, "Link di condivisione".

Formati: JPEG, PNG, GIF, BMP, WebP, **HEIC** (iPhone) e **video** MP4/MOV.
HEIC e video richiedono programmi esterni, che nel container ci sono: sul PC
servono `heif-convert` (pacchetti `libheif-examples` e
`libheif-plugin-libde265`) e `ffmpeg`. Senza, l'app funziona lo stesso e
rifiuta solo quei formati.

- Gli **HEIC** restano HEIC nell'archivio; al browser arriva una "vista" JPEG.
- I **video** prendono data, posizione e durata dal file; l'anteprima è un
  fotogramma. Quelli H.264 con audio AAC/MP3 si riproducono così come sono.
  Gli altri (HEVC dei MOV recenti dell'iPhone, VP9, ProRes, H.264 a 10 bit,
  audio PCM), che non tutti i browser leggono, ricevono in sottofondo una
  **versione compatibile** H.264/AAC in MP4, al massimo 1080p: un video alla
  volta, con ffmpeg a priorità bassa. Finché non c'è, il visore avvisa "in
  conversione" e offre l'originale da scaricare. Per i video già in archivio
  c'è **Converti video** (admin). L'originale non si tocca: i convertiti
  stanno in `<archivio>/.compatibili/<id>.mp4`.

## Sul server

Su server2 FotoTimeline gira dietro HTTPS con il login del Keycloak di presenze
(realm `fototimeline`), e gli originali stanno su LifetimeCloud, montato con
rclone solo quando un amministratore lo chiede dall'app. Tutto in
[DEPLOY.md](DEPLOY.md).

## Avvio sul PC

Serve MongoDB su `localhost:27017`:

```bash
docker compose up -d        # oppure un mongod già installato
```

Poi il backend, che serve anche l'interfaccia già compilata:

```bash
cd backend
./mvnw spring-boot:run      # o: mvn spring-boot:run
```

e si apre <http://localhost:8080>.

### Sviluppo del frontend

```bash
cd frontend
npm install
npm start                   # http://localhost:4200, /api va al backend sulla 8080
npm run build               # ricompila dentro backend/src/main/resources/static
```

### Un solo jar

```bash
cd frontend && npm run build && cd ../backend && mvn package
java -jar target/fototimeline-1.0.0.jar
```

## Configurazione

| Variabile               | Predefinito                               | A cosa serve                         |
|-------------------------|-------------------------------------------|--------------------------------------|
| `FOTOTIMELINE_ARCHIVIO` | `~/FotoTimeline`                          | Cartella di originali e miniature    |
| `MONGODB_URI`           | `mongodb://localhost:27017/fototimeline`  | Database dei metadati                |
| `FOTOTIMELINE_INDIRIZZO`| `127.0.0.1`                               | Indirizzo su cui ascolta il server   |
| `FOTOTIMELINE_MINIATURE`| `<archivio>/.miniature`                   | Cartella delle miniature             |
| `FOTOTIMELINE_IMPORTAZIONE` | (ovunque)                             | Radice consentita per "Importa cartella" |
| `FOTOTIMELINE_LOGIN`    | `false`                                   | Login Keycloak (acceso dal profilo `server`) |
| `RCLONE_RC_URL`         | (vuoto)                                   | API di rclone per montare il cloud; vuoto = disco locale |
| `FOTOTIMELINE_CARTELLA_AUTOMATICA` | (vuoto)                        | Cartella svuotata da sola nell'archivio; vuoto = spenta |
| `FOTOTIMELINE_INTERVALLO_AUTOMATICO` | `PT15M`                      | Ogni quanto controllarla                 |
| `FOTOTIMELINE_TELEFONO_PROPRIETARIO` | (vuoto)                      | Di chi è il telefono migrato da quando era uno solo; vuoto = del primo admin che entra |
| `FOTOTIMELINE_LUOGHI`   | (vuoto; nel container `/app/geonames`)    | Cartella dei file di GeoNames; vuoto = niente luoghi |
| `FOTOTIMELINE_LUOGHI_DISTANZA` | `30`                               | Km oltre i quali il luogo è sconosciuto  |
| `FOTOTIMELINE_VIDEO_ATTIVA` | `true`                                | Versione compatibile dei video non H.264 |
| `FOTOTIMELINE_VIDEO_RISOLUZIONE` | `1080`                           | Lato corto massimo dei convertiti (i 4K scendono) |
| `FOTOTIMELINE_VIDEO_THREAD` | `2`                                   | Thread di ffmpeg per una conversione     |
| `FOTOTIMELINE_VIDEO_DESTINAZIONE` | `<archivio>/.compatibili`       | Dove vanno i convertiti                  |
| `FOTOTIMELINE_QUASI_UGUALI_SOGLIA` | `6`                            | Bit di differenza tra le impronte per dire "quasi uguali" |
| `FOTOTIMELINE_TELEGRAM_TOKEN`, `FOTOTIMELINE_TELEGRAM_CHAT` | (vuoti) | Avvisi su Telegram della pagina "Salute"; vuoti = spenti |
| `FOTOTIMELINE_TELEGRAM_ATTENZIONE` | `false`                        | Avvisa anche quando una voce diventa gialla |
| `FOTOTIMELINE_TELEGRAM_CONTROLLO` | `PT15M`                         | Ogni quanto controllare per gli avvisi   |
| `FOTOTIMELINE_DOMINIO`  | (vuoto)                                   | Il link all'app nei messaggi di Telegram |

Sul PC il server ascolta solo su `127.0.0.1`: non c'è login e *Importa
cartella* legge qualunque cartella. Con il login spento e un altro indirizzo
l'app si rifiuta di partire; per aprirla in rete si usa il profilo `server`
(login Keycloak), come su server2.

**WSL**: se il backend gira in WSL, le cartelle di Windows si importano con il
percorso Linux, per esempio `/mnt/c/Users/Marco/Pictures`.

## API

| Metodo | Percorso                     | Cosa fa                                                    |
|--------|------------------------------|------------------------------------------------------------|
| GET    | `/api/foto`                  | Pagina di foto: `q`, `tag`, `album`, `preferite`, `nazione` (codice ISO), `regione`, `luogo`, `caricataDa` (username), `dal`, `al`, `pagina`, `dimensione` |
| GET    | `/api/timeline`              | Mesi con il numero di foto (stessi filtri, senza date)     |
| POST   | `/api/foto`                  | Caricamento multipart (`file` ripetuto, `album` opzionale) |
| POST   | `/api/importa`               | Avvia in sottofondo: `{ "cartella": "...", "albumDaCartella": true, "sposta": false }` |
| GET    | `/api/importazioni/corrente` | Importazione in corso o ultima finita (204 se nessuna)     |
| POST   | `/api/importazioni/annulla`  | Ferma quella in corso                                      |
| GET    | `/api/cartelle`              | Sottocartelle per il navigatore (`percorso` opzionale)     |
| GET    | `/api/io`                    | Utente collegato (`username`, `nome`), se è admin, radice di importazione; registra l'utente in `utenti` |
| GET    | `/api/utenti`                | Utenti entrati almeno una volta: username, nome, email, primo e ultimo accesso, admin (ruolo `fototimeline-admin`) |
| GET    | `/api/caricate-da`           | Chi ha portato foto e quante (`username`, `nome`, `conteggio`), per il filtro |
| GET    | `/api/cloud`                 | Cloud montato o no                                         |
| GET    | `/api/backup`                | Backup dei metadati presenti                               |
| POST   | `/api/backup`                | Fa subito un backup (ruolo `fototimeline-admin`)           |
| POST   | `/api/cloud/monta`, `/smonta`| Monta o smonta il cloud (ruolo `fototimeline-admin`)       |
| GET    | `/api/telefoni`              | `{ disponibile, motivo, destinazione, telefoni: [...] }`: ogni telefono con impostazioni, `inCorso`/`inCoda`, ultimo giro, giro in corso, prossimo giro. L'admin li vede tutti, gli altri solo i propri |
| POST   | `/api/telefoni`              | Aggiunge un telefono: `{ nome, proprietario, sorgente, attiva, intervalloOre, giorniPrimaDiCancellare, copieInParallelo }` (201; 400 fuori misura o cartella già usata; admin) |
| PUT    | `/api/telefoni/{id}`         | Cambia un telefono, stessi campi (null = com'è; admin) |
| DELETE | `/api/telefoni/{id}`         | Toglie un telefono; il registro delle copie resta (204; 409 durante un giro; admin) |
| POST   | `/api/telefoni/{id}/sincronizza` | Un giro subito, in coda dietro agli altri: 202, 409 se già in coda (admin o proprietario) |
| POST   | `/api/archivio/indicizza`    | "Indicizza archivio" in sottofondo, stato come `/api/importa` (ruolo `fototimeline-admin`) |
| GET    | `/api/archivio/video`        | Coda dei video da convertire: `fatti`, `daFare`, `corrente`, `percentuale`, `inAttesa` (cloud smontato) |
| POST   | `/api/archivio/video/converti` | "Converti video": mette in coda i video già in archivio che ne hanno bisogno, 202 (admin) |
| POST   | `/api/archivio/video/annulla`  | Ferma quello in corso e svuota la coda (admin)          |
| GET    | `/api/quasi-uguali`          | Gruppi di foto quasi uguali (`pagina`, `dimensione`), dal più numeroso, con la `suggerita` da tenere |
| POST   | `/api/quasi-uguali/risolvi`  | `{ tieni: [id], togli: [id] }`: elimina le `togli` come `DELETE /api/foto/{id}` (503 col cloud smontato, 400 per una preferita) |
| POST   | `/api/quasi-uguali/ignora`   | `{ ids: [id] }`: "non sono doppioni", il gruppo non ricompare |
| GET    | `/api/quasi-uguali/calcola`  | "Calcola impronte": stato, avanzamento e foto ancora senza impronta |
| POST   | `/api/quasi-uguali/calcola`  | Lo avvia in sottofondo: 202, 409 se già in corso (ruolo `fototimeline-admin`); `/calcola/annulla` lo ferma |
| GET    | `/api/salute`                | `{ stato, voci: [{ chiave, titolo, stato, messaggio, dettagli }], controllatoIl, avvisiTelegram }`, stato `OK`/`ATTENZIONE`/`ERRORE` (admin) |
| POST   | `/api/salute/prova`          | Messaggio di prova su Telegram: 204, 409 se non configurato, 502 se Telegram rifiuta (admin) |
| GET    | `/salute`                    | Healthcheck di Docker: `ok`, senza login                   |
| GET    | `/api/foto/{id}`             | Una foto                                                   |
| PUT    | `/api/foto/{id}`             | Modifica titolo, descrizione, tag, album, preferita, data  |
| DELETE | `/api/foto/{id}`             | Elimina foto e file                                        |
| POST   | `/api/foto/multiple`         | `{ ids, operazione, valore }` su più foto                  |
| GET    | `/api/foto/{id}/miniatura`   | Miniatura JPEG                                             |
| GET    | `/api/foto/{id}/file`        | Il file, con `Range` per i video: la versione compatibile se c'è, altrimenti l'originale; `?originale=true` o `?scarica=true` (allegato) per l'originale |
| GET    | `/api/foto/{id}/vista`       | Quello che il browser sa mostrare (JPEG per gli HEIC)      |
| GET    | `/api/tag`, `/api/album`     | Elenchi per i filtri                                       |
| GET    | `/api/mappa`                 | Foto con GPS (stessi filtri della timeline), col nome del luogo |
| GET    | `/api/luoghi`                | `{ disponibile, daCalcolare, nazioni: [{ codice, nome, conteggio, regioni: [{ nome, conteggio, luoghi: [{ nome, conteggio }] }] }] }`, i più fotografati prima |
| POST   | `/api/archivio/luoghi`       | "Calcola luoghi" in sottofondo, stato come `/api/importa`; `?tutte=true` rifà anche quelle che ce l'hanno (ruolo `fototimeline-admin`; 409 senza dataset) |
| GET    | `/api/ricordi`               | Stesso giorno negli anni passati (`data` opzionale)        |

Link di condivisione (dietro login come il resto):

| Metodo | Percorso                     | Cosa fa                                                    |
|--------|------------------------------|------------------------------------------------------------|
| POST   | `/api/condivisioni`          | Crea: `{ titolo, ids \| album \| dal+al, giorni: 1/7/30/null, download, posizione }` → 201 col token |
| GET    | `/api/condivisioni`          | I link dell'utente (l'admin li vede tutti), con visite e ultimo accesso |
| DELETE | `/api/condivisioni/{id}`     | Revoca (solo chi l'ha creato o l'admin; per gli altri 404)  |

**Pubbliche, senza login** (solo GET; ogni richiesta controlla il token):

| Metodo | Percorso                                        | Cosa fa                                        |
|--------|-------------------------------------------------|------------------------------------------------|
| GET    | `/c/{token}`                                    | La pagina (index.html dell'app, che mostra solo la galleria) |
| GET    | `/api/condivise/{token}`                        | Titolo, scadenza, download, foto; 404 link sconosciuto, 410 scaduto o revocato |
| GET    | `/api/condivise/{token}/foto/{id}/miniatura`    | Miniatura                                      |
| GET    | `/api/condivise/{token}/foto/{id}/vista`        | JPEG al massimo di 2048 px, senza EXIF         |
| GET    | `/api/condivise/{token}/foto/{id}/video`        | Il video (originale, con `Range`)              |
| GET    | `/api/condivise/{token}/foto/{id}/originale`    | Originale da scaricare (403 senza download)    |
| GET    | `/api/condivise/{token}/zip`                    | Tutti gli originali in uno zip (403 senza download) |

### Link di condivisione

- **Chi li crea**: ogni utente collegato (vede già tutte le foto, e il link
  porta il suo nome); ognuno vede e revoca i suoi, l'admin tutti.
- **Token**: 256 bit da `SecureRandom` in base64url (43 caratteri). È
  salvato in chiaro in `condivisioni`, perché chi ha creato il link possa
  ricopiarlo: chi legge il database può aprire i link.
- **Le foto sono fissate alla creazione**: un album che cresce dopo non
  allarga il link; le foto eliminate spariscono. Al massimo 2000 per link.
- **Una foto che non è nel link risponde 404**, come un token sconosciuto.
- **Chi ha il link vede** titolo del link, data di scatto, titolo della foto,
  dimensioni e durata dei video. **Non vede** nome del file, percorso nel
  cloud, descrizione, tag, album, fotocamera, hash, chi ha creato il link; la
  posizione GPS solo se spuntata ("Includi la posizione", spenta di solito).
- **Le foto si vedono da un JPEG ridotto e senza metadati**, fatto la prima
  volta dall'originale e tenuto in `<miniature>/condivise/`. I **video** si
  guardano dall'originale anche senza download (altrimenti non si
  guarderebbero): il file può contenere la posizione registrata dal telefono.
  Con il download permesso, originali e zip sono i file come sono, EXIF e GPS
  compresi (il dialogo lo dice).
- **Zip** scritto direttamente sulla risposta, un file alla volta a pezzi di
  64 KB (la JVM ha 384 MB). JPEG e video non si ricomprimono (deflate livello
  0); non STORED perché vorrebbe CRC e dimensione prima di ogni file, cioè
  leggerlo due volte dal cloud. Nei download i nomi sono la data di scatto
  (`2024-05-17 14.03.22.jpg`), non quelli dell'archivio.
- **Cloud smontato**: la galleria e le miniature si vedono; vista non ancora
  preparata, originali e zip rispondono 503 con un messaggio per chi ha il link.
- **Freno per IP** (`LimiteRichieste`, in memoria): 1200 richieste al minuto
  sui path pubblici e, dopo 20 link sconosciuti (o foto fuori dal link) in 15
  minuti, 429 per 15 minuti. Gli IPv6 contano per la loro /64.
  `fototimeline.condivisioni.*` in `application.yml`.
- La pagina risponde con `Referrer-Policy: no-referrer` e `X-Robots-Tag: noindex`.

## Test

```bash
cd backend && mvn test
```

I test girano su un MongoDB embedded (flapdoodle): la prima volta scaricano
il binario di MongoDB. Per i luoghi usano un mini dataset di GeoNames in
`src/test/resources/geonames` (Roma, Sperlonga, Parigi, ...).

### Luoghi sul PC

Il dataset non sta nel repository. Per avere i luoghi anche sul PC:

```bash
mkdir -p ~/geonames && cd ~/geonames
curl -O https://download.geonames.org/export/dump/cities500.zip && unzip cities500.zip
curl -O https://download.geonames.org/export/dump/admin1CodesASCII.txt
curl -O https://download.geonames.org/export/dump/countryInfo.txt
export FOTOTIMELINE_LUOGHI=~/geonames      # poi ./mvnw spring-boot:run
```

Così i nomi stranieri restano quelli di GeoNames (Rome, Paris); i nomi in
italiano vengono da `alternateNames-it.txt`, che fa il Dockerfile (stage
`geonames`).

## CI su server2

La GitHub Action `.github/workflows/test.yml` (test del backend e build del
frontend, a ogni push su `main` e su ogni pull request) gira sul runner
self-hosted di **server2**, la macchina che fa già le build di TrovaCampo e
presenze.

Un runner self-hosted su un account personale vale per un solo repository,
quindi quelli di TrovaCampo e presenze non vedono i job di FotoTimeline: ne
serve uno suo, in una cartella sua. Una volta sola, su server2:

1. **Settings → Actions → Runners → New self-hosted runner** di
   `cardinal76/fototimeline`, sistema Linux x64.
2. Seguire le righe proposte in una cartella nuova, per esempio
   `~/actions-runner-fototimeline`; a `./config.sh` dare il nome
   `server2-fototimeline` e l'etichetta:

   ```
   fototimeline-build
   ```

3. Installarlo come servizio, perché sopravviva a un riavvio:

   ```bash
   sudo ./svc.sh install && sudo ./svc.sh start
   ```

Java 21 e Node 22 li scaricano le azioni `setup-java` e `setup-node` nella
cache del runner; non serve Docker. Finché il runner non è registrato e
acceso, i job restano in coda.
