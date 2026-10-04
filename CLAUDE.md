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
  momento del caricamento. `origineData` dice quale è stata usata; una
  modifica a mano la mette a `MANUALE`.

## Mongo: trappole note

- Niente `distinct` su `tag`: Mongo restituisce gli array vuoti come
  `undefined` e il driver va in errore leggendoli come stringhe. Si usa
  un'aggregazione con `unwind` (vedi `FotoService.tag()`).
- `hash` (SHA-256 del file) ha un indice unico: è così che la stessa foto non
  entra due volte, anche con due caricamenti in parallelo.

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
  token: li legge `RuoliKeycloak`. `POST /api/cloud/**` vuole
  `fototimeline-admin` (`fototimeline.login.ruolo-admin`).
- CSRF sempre acceso: cookie `XSRF-TOKEN`, Angular lo rimanda da solo in
  `X-XSRF-TOKEN`. In Spring Security 6.5 non c'è `csrf().spa()`: lo fanno
  `CsrfPerSpa` e `CookieCsrfSempre`. Il logout è un form POST con `_csrf`
  (`sessione.ts`), perché la risposta porta a Keycloak, su un'altra origine.
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
- Cartelle e cartella automatica riconoscono i file con `FotoService.TIPI`:
  entrano anche HEIC e video, e `importa` passa il `Path`, mai i byte.

## Git e CI

- Lavora su un branch e apri una pull request verso `main`; la GitHub Action
  `.github/workflows/test.yml` lancia `./mvnw verify` e `npm run build`.
- Prima di fare push esegui gli stessi due comandi in locale.
- Il rilascio su server2 parte quando il branch `produzione` riceve un push
  (`.github/workflows/rilascio.yml`): fallo solo quando Marco lo chiede.
