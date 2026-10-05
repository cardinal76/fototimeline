# FotoTimeline

Gestore di foto con timeline, da usare in locale sul proprio PC.

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
- **Foto dal telefono via pCloud** (admin, sul server): ogni tot ore copia le
  foto nuove da pCloud ("Automatic Upload") nella cartella automatica e, dopo
  qualche giorno, le toglie da pCloud se sono state importate (DEPLOY.md).
- **Visore** a schermo intero: ← → per scorrere, `F` preferita, `I` pannello
  informazioni, `Esc` chiude. Dal pannello si modificano titolo, descrizione,
  tag, album, data; si scarica l'originale o si elimina.
- **Mappa**: le foto con posizione GPS su OpenStreetMap, raggruppate quando
  sono vicine; dal popup si apre la foto. Rispetta i filtri.
- **Accadde oggi**: in cima alla timeline le foto dello stesso giorno negli
  anni passati ("3 anni fa"); si chiude fino al giorno dopo.
- **App sul telefono**: dal browser "Aggiungi a schermata Home" / "Installa
  app" (manifest, icone, service worker); si apre a schermo intero.
- **Backup dei metadati**: ogni giorno una copia di date, titoli, tag e album
  nell'archivio (`.backup/`), ripristinabile con `mongoimport`.
- **Ricerca e filtri**: testo libero (titolo, descrizione, file, tag, album,
  fotocamera), tag, album, solo preferite.
- **Selezione multipla** (bottone *Seleziona* o Ctrl+clic): aggiungi/togli
  tag, sposta in un album, segna preferite, elimina; "seleziona giorno" per
  prendere un giorno intero.

Formati: JPEG, PNG, GIF, BMP, WebP, **HEIC** (iPhone) e **video** MP4/MOV.
HEIC e video richiedono programmi esterni, che nel container ci sono: sul PC
servono `heif-convert` (pacchetti `libheif-examples` e
`libheif-plugin-libde265`) e `ffmpeg`. Senza, l'app funziona lo stesso e
rifiuta solo quei formati.

- Gli **HEIC** restano HEIC nell'archivio; al browser arriva una "vista" JPEG.
- I **video** prendono data, posizione e durata dal file; l'anteprima è un
  fotogramma. Si riproducono nel browser così come sono: H.264 ovunque, HEVC
  (i MOV recenti dell'iPhone) solo dove il browser lo supporta (Safari, Chrome
  con accelerazione hardware).

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

Sul PC il server ascolta solo su `127.0.0.1`: non c'è login e *Importa
cartella* legge qualunque cartella. Con il login spento e un altro indirizzo
l'app si rifiuta di partire; per aprirla in rete si usa il profilo `server`
(login Keycloak), come su server2.

**WSL**: se il backend gira in WSL, le cartelle di Windows si importano con il
percorso Linux, per esempio `/mnt/c/Users/Marco/Pictures`.

## API

| Metodo | Percorso                     | Cosa fa                                                    |
|--------|------------------------------|------------------------------------------------------------|
| GET    | `/api/foto`                  | Pagina di foto: `q`, `tag`, `album`, `preferite`, `dal`, `al`, `pagina`, `dimensione` |
| GET    | `/api/timeline`              | Mesi con il numero di foto (stessi filtri, senza date)     |
| POST   | `/api/foto`                  | Caricamento multipart (`file` ripetuto, `album` opzionale) |
| POST   | `/api/importa`               | Avvia in sottofondo: `{ "cartella": "...", "albumDaCartella": true, "sposta": false }` |
| GET    | `/api/importazioni/corrente` | Importazione in corso o ultima finita (204 se nessuna)     |
| POST   | `/api/importazioni/annulla`  | Ferma quella in corso                                      |
| GET    | `/api/cartelle`              | Sottocartelle per il navigatore (`percorso` opzionale)     |
| GET    | `/api/io`                    | Utente collegato, se è admin, radice di importazione       |
| GET    | `/api/cloud`                 | Cloud montato o no                                         |
| GET    | `/api/backup`                | Backup dei metadati presenti                               |
| POST   | `/api/backup`                | Fa subito un backup (ruolo `fototimeline-admin`)           |
| POST   | `/api/cloud/monta`, `/smonta`| Monta o smonta il cloud (ruolo `fototimeline-admin`)       |
| GET    | `/api/telefono`              | Sincronizzazione del telefono: impostazioni, ultimo giro, `disponibile`/`motivo`, `inCorso` (ruolo `fototimeline-admin`) |
| PUT    | `/api/telefono`              | `{ attiva, sorgente, intervalloOre, giorniPrimaDiCancellare }` (400 fuori misura; admin) |
| POST   | `/api/telefono/sincronizza`  | Un giro subito, in sottofondo: 202, 409 se già in corso (admin) |
| POST   | `/api/archivio/indicizza`    | "Indicizza archivio" in sottofondo, stato come `/api/importa` (ruolo `fototimeline-admin`) |
| GET    | `/api/foto/{id}`             | Una foto                                                   |
| PUT    | `/api/foto/{id}`             | Modifica titolo, descrizione, tag, album, preferita, data  |
| DELETE | `/api/foto/{id}`             | Elimina foto e file                                        |
| POST   | `/api/foto/multiple`         | `{ ids, operazione, valore }` su più foto                  |
| GET    | `/api/foto/{id}/miniatura`   | Miniatura JPEG                                             |
| GET    | `/api/foto/{id}/file`        | Originale (`?scarica=true` per scaricarlo); con `Range` per i video |
| GET    | `/api/foto/{id}/vista`       | Quello che il browser sa mostrare (JPEG per gli HEIC)      |
| GET    | `/api/tag`, `/api/album`     | Elenchi per i filtri                                       |
| GET    | `/api/mappa`                 | Foto con GPS (stessi filtri della timeline)                |
| GET    | `/api/ricordi`               | Stesso giorno negli anni passati (`data` opzionale)        |

## Test

```bash
cd backend && mvn test
```

I test girano su un MongoDB embedded (flapdoodle): la prima volta scaricano
il binario di MongoDB.

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
