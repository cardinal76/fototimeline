# FotoTimeline

Gestore di foto con timeline. Gira in due modi: sul PC di Marco (niente
login, solo `127.0.0.1`, archivio su disco) e su **server2** (profilo `server`:
login Keycloak, HTTPS con Caddy, originali su LifetimeCloud montato da rclone).
Il deploy è in `DEPLOY.md`. Backend Spring Boot 3.5 / Java 21 / Maven / MongoDB in
`backend`, frontend Angular 20 standalone (zoneless, signals, nessuna
libreria UI) in `frontend`. Il codice, i commenti e i messaggi di commit sono
in italiano, come i nomi di classi e metodi (`FotoService`, `importa`,
`scattataIl`): continua così.

## Dove stanno le foto

- **I file stanno su disco, Mongo tiene solo i metadati.** L'archivio è
  `fototimeline.archivio` (`FOTOTIMELINE_ARCHIVIO`, predefinito
  `~/FotoTimeline`). Gli originali vanno in una cartella per giorno di scatto,
  `AAAA/MM/GG/<nome originale>`; le miniature in `.miniature/<id>.jpg`.
- `Foto.percorso` è relativo alla radice dell'archivio, sempre con `/`.
  Ogni accesso a un file passa da `ArchivioFile.originale(...)`, che rifiuta i
  percorsi fuori dalla radice: non costruire `Path` a mano.
- Nomi uguali nello stesso giorno diventano `IMG_0001 (2).jpg`
  (`ArchivioFile.conNumero`), scritti con `CREATE_NEW`: un file esistente non
  si sovrascrive mai. `nomeSicuro` toglie cartelle e caratteri vietati da
  Windows.
- Se cambia la data di scatto il file si sposta (`FotoService.mettiAlSuoPosto`)
  e le cartelle rimaste vuote si cancellano risalendo fino alla radice.
  All'avvio `RiordinoAllAvvio` rimette a posto tutto quello che non sta nella
  cartella del suo giorno: ogni cambio alla struttura delle cartelle deve
  passare anche da lì, così gli archivi esistenti si migrano da soli.

## Formati: foto, HEIC, video

- `FotoService.importa(nome, Path, ...)` lavora su file, mai con tutto il
  contenuto in memoria: i video pesano gigabyte e la JVM sul server ha 384 MB.
  Anche i caricamenti dal browser passano da un file temporaneo
  (`transferTo`). Non tornare a `byte[]` / `readAllBytes`.
- `Genere`: FOTO (ImageIO + metadata-extractor), HEIC (`heif-convert` →
  JPEG per miniatura e "vista"), VIDEO (`ffprobe` per dimensioni, durata,
  data e GPS; `ffmpeg` per un fotogramma). Tutto in `StrumentiMedia`; se un
  programma manca quel formato viene rifiutato, il resto funziona.
- Per leggere gli HEIC serve anche `libheif-plugin-libde265` (decoder HEVC):
  senza, `heif-convert` dice "Unsupported codec".
- Le date dei video: `com.apple.quicktime.creationdate` ha il fuso ed è già
  ora locale; `creation_time` è UTC e si converte nel fuso della JVM (nel
  container `TZ=Europe/Rome`). Le date prima del 1990 sono "sconosciuta".
- Il browser riceve `/vista` (JPEG per gli HEIC, l'originale per il resto) e
  per i video `/file`, che risponde a pezzi con `Range`.
- Nei test i casi HEIC e video si saltano dove i programmi mancano
  (`assumeTrue`): i file di prova sono in `src/test/resources`.

## Date

- `scattataIl` è un `LocalDateTime` senza fuso, come lo scrive la fotocamera
  nell'EXIF (letto in UTC e riconvertito in UTC, così resta l'ora scritta).
  `giorno` ("yyyy-MM-dd") si aggiorna nel setter di `scattataIl` e serve per
  raggruppare la timeline senza problemi di fuso: non impostarlo a parte.
- Ordine di precedenza: EXIF → data del file (importazione da cartella) →
  momento del caricamento. Per "Indicizza archivio": EXIF → cartella
  `AAAA/MM/GG` (`CARTELLA`) → data di modifica del file. `origineData` dice quale è stata usata; una
  modifica a mano la mette a `MANUALE`.

## Mongo: trappole note

- Niente `distinct` su `tag`: Mongo restituisce gli array vuoti come
  `undefined` e il driver va in errore leggendoli come stringhe. Si usa
  un'aggregazione con `unwind` (vedi `FotoService.tag()`).
- `hash` (SHA-256 del file) ha un indice unico: è così che la stessa foto non
  entra due volte, anche con due caricamenti in parallelo.

## Mappa, ricordi, PWA

- La mappa (`mappa.ts`, Leaflet + leaflet.markercluster) si carica con
  `@defer` solo quando la si apre. markercluster si aggancia alla `L` globale:
  `window.L = L` prima di `import('leaflet.markercluster')`. I marker sono
  `divIcon` con la miniatura (niente immagini di Leaflet da copiare); i video
  (`PuntoMappa.video`) hanno un ▶ sopra la miniatura e nel popup.
- "Accadde oggi" (`ricordi.ts`, `GET /api/ricordi`) cerca `giorno` che finisce
  con `-MM-GG` negli anni prima di quello corrente.
- PWA: `public/manifest.webmanifest`, `public/icone/`, `public/sw.js` (solo
  pagina offline, non mette in cache foto né API: sono private). Questi file
  sono `permitAll` in `ConfigurazioneSicurezza`, perché il browser li chiede
  senza cookie; `ConfigurazioneWeb` dà al manifest il tipo
  `application/manifest+json`.
- Nei test con Playwright non usare `page.clock.setFixedTime`: ferma i timer
  e Leaflet non si disegna.

## Backup dei metadati

- `BackupMetadati` scrive `<archivio>/.backup/fototimeline-*.json.gz`: un
  documento della collezione `foto` per riga, Extended JSON relaxed (si
  ripristina con `mongoimport --mode upsert`, DEPLOY.md). Prima un file
  `.parziale`, poi lo spostamento: mai un backup a metà col nome buono.
- Ogni ora controlla se l'ultimo è più vecchio dell'intervallo: col cloud
  smontato salta e recupera appena montato. `POST /api/backup` è da admin.
- Un campo nuovo in `Foto` finisce nel backup da solo (si copia il documento
  grezzo): non serve toccare il backup.

## Comandi

```bash
docker compose up -d                          # MongoDB su localhost:27017
cd backend && ./mvnw spring-boot:run          # http://localhost:8080
cd backend && ./mvnw verify                   # test
cd frontend && npm start                      # dev server sulla 4200, /api → 8080
cd frontend && npm run build                  # compila in backend/src/main/resources/static
```

- I test del backend sono d'integrazione su un MongoDB embedded (flapdoodle,
  la prima volta scarica il binario): `@SpringBootTest`, archivio in una
  `@TempDir`. Le immagini di prova si generano con `ImageIO`; per l'EXIF c'è
  `src/test/resources/con-exif.jpg`.
- La build di Angular finisce in `backend/src/main/resources/static`, che è
  in `.gitignore`: non va committata.
- Il frontend non ha test; la CI controlla che compili.

## Sicurezza

- **Login spento** (PC): tutto aperto, ma `ConfigurazioneSicurezza` non fa
  partire l'app se `server.address` non è di loopback. Non togliere quel
  controllo.
- **Login acceso** (profilo `server`): OIDC con `oauth2Login` sul Keycloak di
  presenze, **realm `fototimeline`**, client confidenziale `fototimeline`.
  Sessione con cookie, non token Bearer: le foto si caricano con `<img>`, che
  non può mandare header. Le API senza sessione rispondono 401 (Angular manda
  a `/oauth2/authorization/keycloak`), le pagine fanno il redirect.
- I ruoli di realm stanno nell'access token (`realm_access.roles`), non nell'ID
  token: li legge `RuoliKeycloak`. `POST /api/cloud/**`, `/api/backup`,
  `/api/archivio/**` e tutto `/api/telefono` vogliono `fototimeline-admin`
  (`fototimeline.login.ruolo-admin`).
- CSRF sempre acceso: cookie `XSRF-TOKEN`, Angular lo rimanda da solo in
  `X-XSRF-TOKEN`. In Spring Security 6.5 non c'è `csrf().spa()`: lo fanno
  `CsrfPerSpa` e `CookieCsrfSempre`. Il logout è un form POST con `_csrf`
  (`sessione.ts`), perché la risposta porta a Keycloak, su un'altra origine.
- **Path pubblici** (senza login, solo GET): `/salute`, `/error`, i file della
  PWA, e per i link di condivisione `/c/*`, `/api/condivise/**` e i bundle di
  Angular (`/main-*.js`, `/chunk-*.js`, `/polyfills-*.js`, `/styles-*.css`:
  codice, niente dati; `index.html` lo serve `CondiviseController` solo da
  `/c/{token}`). Ogni endpoint sotto `/api/condivise/{token}` passa da
  `CondivisioniService.valida` (token, scadenza, revoca) e, per una foto, da
  `foto(c, id)` (404 se non è nel link). Una risposta pubblica nuova usa
  record suoi (`FotoCondivisa`), mai `Foto`: niente percorso, nome del file,
  tag, descrizione, fotocamera, GPS (salvo `posizione`). Le immagini passano
  dalla vista ridotta senza EXIF (`vistaCondivisa`). `LimiteRichieste` frena
  per IP. Non allargare i `permitAll` senza un test in `CondivisioniTest`.
- La pagina `/c/<token>` è la stessa app: `main.ts` vede `/c/` e avvia solo
  `Condivisa` (con `fetch`, senza l'interceptor del 401), così non parte
  nessuna chiamata privata e nessun redirect a Keycloak.
- `fototimeline.importazione` limita "Importa cartella" e il navigatore a una
  radice (sul server `/cloud`). L'archivio e le miniature si saltano sempre.

## Cloud e montaggio

- Il cloud lo monta un container `rclone rcd` (solo lui ha FUSE e il token);
  l'app lo comanda via API rc (`Cloud`): `mount/mount`, `mount/unmount`,
  `mount/listmounts`. rclone risponde JSON con `Content-Type: text/plain`: si
  legge come stringa. Le opzioni del VFS vanno nella richiesta, in forma
  numerica (`CacheMode` 3 = full, durate in nanosecondi): `rclone rcd` non
  accetta i flag `--vfs-*` in tutte le versioni.
- Ogni accesso agli originali passa da `ArchivioFile`, che chiama
  `cloud.verifica()`: da smontato lancia `ArchivioNonDisponibile` (503). Così
  niente viene scritto nel punto di montaggio vuoto, cioè sul disco di server2.
  Un metodo nuovo che tocca gli originali deve passare da lì; per lo stesso
  motivo `ArchivioFile` non crea la radice all'avvio.
- Miniature e MongoDB stanno su server2: da smontato la timeline si vede.
  `FotoService.fileMiniatura` rifà una miniatura mancante dall'originale.
- Dopo un riavvio il cloud è smontato, salvo `RCLONE_MONTA_ALL_AVVIO=true`.

## Importazioni

- Le importazioni da cartella girano in sottofondo in `LavoriImportazione`, una
  alla volta su un solo thread: `POST /api/importa` risponde 202 con lo stato,
  il frontend lo segue con `GET /api/importazioni/corrente` (ogni secondo
  mentre è in corso, ogni minuto altrimenti, per vedere quelle automatiche).
- `ImportazioneCartelle.importa` resta sincrona e riceve un `Avanzamento`
  (avanzamento e annullamento): i test la usano direttamente.
- La cartella automatica (`fototimeline.importazione-automatica.cartella`) la
  controlla un `@Scheduled`: solo col cloud montato e nessuna importazione in
  corso; importa spostando, con le sottocartelle come album.
- "Indicizza archivio" (`POST /api/archivio/indicizza`, admin) è un lavoro
  dello stesso `LavoriImportazione` (origine `INDICIZZAZIONE`):
  `ImportazioneCartelle.indicizza` percorre l'archivio saltando le cartelle col
  punto e `FotoService.indicizza` dà la scheda ai file senza, lasciandoli dove
  sono (o nella cartella del loro giorno). La lettura è la stessa di `importa`
  (`FotoService.leggi`): un cambio ai formati vale per entrambi.
- Cartelle e cartella automatica riconoscono i file con `FotoService.TIPI`:
  entrano anche HEIC e video, e `importa` passa il `Path`, mai i byte.

## Telefono (pCloud)

- `SincronizzazioneTelefono` (pacchetto `telefono`) copia i file nuovi da
  pCloud (`sorgente`, per esempio `pcloud:Automatic Upload`) nella cartella
  automatica, tutto con l'API rc (`Rclone.chiama`), non col montaggio:
  `operations/list` (ricorsivo, solo file), `operations/copyfile`,
  `operations/stat` (`{"item": null}` se manca), `operations/deletefile`.
  La destinazione è la cartella automatica relativa al punto di montaggio
  (`/cloud/telefono` → `lifetime:telefono`), senza sottocartelle (`/` → ` - `).
- Registro `copie_telefono` (sorgente, percorso, dimensione): un file nel
  registro non si ricopia mai. Dopo `giorniPrimaDiCancellare` si toglie da
  pCloud solo se non è più nella cartella automatica (l'ha importato).
- Impostazioni e ultimo giro nel documento `impostazioni/sincronizzazione-telefono`,
  scritti con update separati. Un giro alla volta (thread `telefono`);
  `@Scheduled` ogni `fototimeline.telefono.controllo` (PT5M) controlla se sono
  passate `intervalloOre`. `/api/telefono/**` è da admin anche in GET.

## Git e CI

- Lavora su un branch e apri una pull request verso `main`; la GitHub Action
  `.github/workflows/test.yml` lancia `./mvnw verify` e `npm run build`.
- Prima di fare push esegui gli stessi due comandi in locale.
- Il rilascio su server2 parte quando il branch `produzione` riceve un push
  (`.github/workflows/rilascio.yml`): fallo solo quando Marco lo chiede.
