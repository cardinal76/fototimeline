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
  contenuto in memoria: i video pesano gigabyte e la JVM sul server ha 1,5 GB
  di heap (`FOTOTIMELINE_JAVA_OPTS`, prima 384 MB).
  Anche i caricamenti dal browser passano da un file temporaneo
  (`transferTo`). Non tornare a `byte[]` / `readAllBytes`.
- Miniature e viste dei link condivisi passano da `media/Riduzione`: l'immagine
  si legge già sottocampionata (`setSourceSubsampling`, al più 8 MP letti),
  mai intera. `Thumbnails.of(file)` decodifica tutto (~460 MB di heap per una
  foto da 50 MP) ed era la causa degli OutOfMemoryError a ogni file nuovo:
  non tornarci. Heap e tetto del container sono nel compose
  (`FOTOTIMELINE_JAVA_OPTS`, `FOTOTIMELINE_MEMORIA_APP`, DEPLOY.md "Memoria").
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
- Video non H.264/AAC (HEVC, VP9, ProRes, 10 bit): `ConversioneVideo` ne fa
  una versione compatibile in `<archivio>/.compatibili/<id>.mp4`
  (`fototimeline.video.*`), un video alla volta, con lo stato sulla scheda
  (`compatibile`, `conversione`): così riprende dopo un riavvio. `/file` serve
  quella se `conversione` è `FATTA`, l'originale con `?originale=true` o
  `?scarica=true`. Col cloud smontato la coda aspetta, non va in errore.
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
- PWA: `public/manifest.webmanifest`, `public/icone/`, `public/sw.js` (pagina
  offline e condivisione dal telefono; non mette in cache foto né API: sono private). Questi file
  sono `permitAll` in `ConfigurazioneSicurezza`, perché il browser li chiede
  senza cookie; `ConfigurazioneWeb` dà al manifest il tipo
  `application/manifest+json`.
- "Condividi → FotoTimeline" (Android): `share_target` nel manifest verso
  `POST /ricevi-condivisi`, che prende `sw.js` e mette i file in IndexedDB
  (`fototimeline-condivisi`; `condivisi-in-attesa.ts` legge lo stesso database:
  nome, versione e campi vanno cambiati insieme) con 303 a `/?condivisi=<lotto>`;
  `ricevi-condivisi.ts` li carica uno alla volta con `POST /api/foto` e li toglie
  appena arrivati. Se la POST arriva al server (niente service worker) il CSRF la
  respinge e `ConfigurazioneSicurezza.condivisiSenzaApp` cambia il 403 in un 303
  a `/?condivisi=senza-app`: niente eccezioni al CSRF, i file non si leggono.
  L'`AccessDeniedHandler` è esplicito: `defaultAccessDeniedHandlerFor` con una
  sola voce varrebbe per tutti gli indirizzi. Le cache di `sw.js` con un altro
  nome si cancellano in `activate`: niente file da tenere in Cache Storage.
- Nei test con Playwright non usare `page.clock.setFixedTime`: ferma i timer
  e Leaflet non si disegna.

## Luoghi dal GPS

- `GeocodificaInversa` (pacchetto `luoghi`) legge all'avvio i file di GeoNames
  da `fototimeline.luoghi.cartella` (`FOTOTIMELINE_LUOGHI`; nel container
  `/app/geonames`, scaricati dallo stage `geonames` del Dockerfile, mai nel
  repository). Senza cartella l'app parte, senza luoghi.
- `IndiceLuoghi` è un k-d tree implicito su vettori unitari 3D: solo array di
  primitivi e i nomi in un `byte[]`, niente oggetti per punto (225 mila punti,
  ~10 MB). Non aggiungere campi per punto senza pensare alla RAM.
- `FotoService.leggi` chiama `applica`: luogo, regione, nazione, codice nazione
  e `luogoCalcolato` (true anche in mare aperto, così "Calcola luoghi" non li
  rifà). "Calcola luoghi" è un lavoro di `LavoriImportazione` (origine
  `LUOGHI`): `importate` = con un luogo, `duplicate` = senza; non chiede il
  cloud montato.
- Il filtro manda `nazione` (codice ISO), `regione`, `luogo`, nell'ordine
  dell'indice composto `luoghi`. L'attribuzione CC BY 4.0 di GeoNames è nel
  filtro e nel visore: va lasciata.
- Nei test: `fototimeline.luoghi.cartella=classpath:geonames` (mini dataset in
  `src/test/resources/geonames`).

## Backup dei metadati

- `BackupMetadati` scrive `<archivio>/.backup/fototimeline-*.json.gz`: un
  documento della collezione `foto` per riga, Extended JSON relaxed (si
  ripristina con `mongoimport --mode upsert`, DEPLOY.md). Prima un file
  `.parziale`, poi lo spostamento: mai un backup a metà col nome buono.
- Ogni ora controlla se l'ultimo è più vecchio dell'intervallo: col cloud
  smontato salta e recupera appena montato. `POST /api/backup` è da admin.
- Un campo nuovo in `Foto` finisce nel backup da solo (si copia il documento
  grezzo): non serve toccare il backup.

## Salute e avvisi

- `Salute` (pacchetto `salute`) fa i controlli della pagina "Salute"
  (`GET /api/salute`, admin; `/salute` senza `/api` è l'healthcheck di Docker:
  non toccarlo). Ogni controllo gira su un thread suo con un tempo massimo
  (`TEMPO_MASSIMO`): se lancia o tarda, la sua voce è rossa e le altre
  restano. Verso rclone un `Rclone` suo con timeout di pochi secondi, non
  quello del montaggio. Una voce nuova: un `Controllo` in `controlla()`, con
  una `chiave` fissa (gli avvisi la ricordano).
- La voce `telefono` riassume tutti i telefoni (`SincronizzazioneTelefono.elenco`):
  il pallino del telefono messo peggio, il suo nome nel messaggio, gli altri
  nei dettagli. Una chiave sola: un telefono in più non cambia gli avvisi.
- `AvvisiSalute` ogni `fototimeline.telegram.controllo` confronta gli stati
  con quelli in `impostazioni/avvisi-salute` e scrive su Telegram solo i
  cambi; se l'invio fallisce non salva, così riprova. Il token del bot sta
  nell'indirizzo: ogni messaggio d'errore passa da `Telegram.senzaToken`.
- "Accadde oggi" su Telegram (`ricordi/RicordiTelegram`): ogni 5 minuti guarda
  se è passata l'ora; il giorno fatto (`ultimoGiorno` in
  `impostazioni/ricordi-telegram`) si prenota con un update condizionato
  *prima* di mandare e si libera se l'invio fallisce: mai due invii al giorno.
  Le immagini: `vistaCondivisa` (senza EXIF) o la miniatura, mai l'originale.
  Il link è `/?ricordi=oggi` (`ricordi.ts`), mai un link pubblico.
- L'esito dell'ultimo backup sta anche in `impostazioni/backup-metadati`
  (`BackupMetadati.esito()`): col cloud smontato i file non si vedono.

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
  `/api/archivio/**`, `/api/utenti` e `/api/salute` vogliono `fototimeline-admin`
  (`fototimeline.login.ruolo-admin`); `/api/telefoni` lo controlla il
  controller (ognuno vede il suo telefono).
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
- Prima di `inizio` c'è la ricerca dei file (sul cloud anche un'ora):
  `Avanzamento.cercando` riferisce i trovati e la cartella, lo stato ha
  `ricerca` e `cercaIn` e il riquadro dice "Cerco i file… N trovati". Annulla
  ferma anche la ricerca (`TERMINATE`, o `takeWhile` in `importa`).
- La cartella automatica (`fototimeline.importazione-automatica.cartella`) la
  controlla un `@Scheduled`: solo col cloud montato e nessuna importazione in
  corso; importa spostando, con le sottocartelle come album.
- Un lavoro chiesto da qualcuno (importa, indicizza, luoghi) ha la precedenza
  sull'automatica: `LavoriImportazione.avvia` la annulla e mette il nuovo in
  coda sullo stesso thread (`inAttesa` finché lei finisce i file in corso);
  l'automatica riprende al giro dopo. Il frontend disabilita i lavori solo
  con `lavoroOccupato()` (in corso e non automatico).
- **Spostare = rinominare.** Con `sposta` `ArchivioFile.salvaOriginale` fa
  `Files.move` se origine e archivio sono sullo stesso file system
  (`stessoDisco`, `unix:dev`): sul server `/cloud/telefono` e
  `/cloud/FotoTimeline` sono lo stesso mount di rclone e la rinomina è un MOVE
  sul cloud, senza scaricare e ricaricare. Su dischi diversi copia (CREATE_NEW)
  e `FotoService` toglie l'origine dopo la scheda. Miniature e anteprime si
  fanno *prima*, dall'origine; se poi la scheda non entra (doppione, errore)
  il file spostato torna nell'origine (`rimetti`), mai cancellato.
- Per file si scarica una volta sola: hash e controllo "cifrato" nella stessa
  lettura (`FotoService.leggi`); metadati e miniatura rileggono dalla cache del
  VFS. "Già presente" lo decide l'hash.
- **Annulla**: nessun file nuovo, e i file in corso smettono alle attese:
  la lettura dell'hash, il turno di `unaAllaVolta` (`tryAcquire` che guarda
  `annullata`) e la riprova (`Avanzamento.aspetta`, svegliata da Annulla).
  Lì `FotoService.Annullata`: il file resta com'era e non si conta. Lo stato
  ha `annullamento` (il riquadro dice "Annullo…").
- "Indicizza archivio" (`POST /api/archivio/indicizza`, admin) è un lavoro
  dello stesso `LavoriImportazione` (origine `INDICIZZAZIONE`):
  `ImportazioneCartelle.indicizza` percorre l'archivio saltando le cartelle col
  punto e `FotoService.indicizza` dà la scheda ai file senza, lasciandoli dove
  sono (o nella cartella del loro giorno). La lettura è la stessa di `importa`
  (`FotoService.leggi`): un cambio ai formati vale per entrambi.
- Importazione e indicizzazione leggono `fototimeline.indicizzazione.paralleli`
  file insieme (`FOTOTIMELINE_INDICIZZA_PARALLELI`, predefinito 3):
  `ImportazioneCartelle.inParallelo` dà a ogni file un thread di piattaforma
  suo (`importazione-file <percorso>`) e un `FileInLavorazione`, e il thread
  del lavoro li sorveglia: annullando non ne parte nessuno nuovo, quelli in
  corso finiscono, si fermano alla prima attesa o, dopo
  `attesa-annullamento`, si abbandonano. Quello che due file fanno insieme deve restare
  sicuro: i doppioni li decide l'indice unico su `hash` (`DuplicateKeyException`
  → DUPLICATA, si toglie la copia del perdente), i nomi in `ArchivioFile` si
  prenotano (`prenotati`: `Files.move` controlla e poi rinomina, non è
  esclusivo) e una cartella tolta da un altro thread perché vuota si ricrea
  (`inCartella`). Miniatura, heif-convert e ffmpeg passano da
  `FotoService.unaAllaVolta` (memoria). `Riepilogo` e `Lavoro` sono
  sincronizzati. I file insieme non superano quanti ne regge l'heap
  (`IndicizzazioneProperties.paralleliPerHeap`: 128 MB di base, 64 MB a file).
- Gli errori di lettura (`FotoService.ILLEGGIBILE`, "File illeggibile (...)":
  il mount che non risponde) si riprovano una volta dopo
  `attesa-riprova` e non sono `erroreDelFile`, quindi la cartella automatica
  non li ricorda. Un'eccezione con un `FileSystemException` o un
  "Input/output error" dentro diventa errore di lettura anche se arriva da
  ImageIO o Thumbnailator (`letturaNonRiuscita`). Nemmeno la memoria finita
  (`OutOfMemoryError`, "Java heap space" nel messaggio, `memoriaFinita`) è un
  errore del file: un ricordo con quel motivo si rilegge al giro dopo.
- **Un file appeso non ferma il lavoro.** Ogni file ha un tempo massimo
  (`tempo-massimo-file` + `tempo-massimo-per-gb`, `FOTOTIMELINE_TEMPO_MASSIMO_FILE`
  e `_PER_GB`, 5 min + 5 min/GB): oltre, chi sorveglia lo abbandona
  (`FileInLavorazione.abbandona`) e lo conta come errore di lettura
  (`ILLEGGIBILE`, la cartella automatica non lo ricorda). Una read() sul mount
  FUSE non si interrompe: il thread resta, ma il turno di `unaAllaVolta`
  torna subito agli altri (`ControlloFile.tieni`, rilascio una volta sola) e
  il file risulta `fermato`: alla prima occasione `Annullata`, e
  `primaDiScrivere` impedisce la scheda (un file già spostato torna
  nell'origine). Esito contato da uno solo: il thread solo se
  `concludi()` dice true. Un metodo nuovo di `FotoService` che lavora un file
  di un'importazione riceve il `ControlloFile`, dice la fase e lo guarda prima
  di scrivere. Le prenotazioni dei nomi di `ArchivioFile` restano al thread
  appeso: liberarle mentre il suo `Files.move` è in volo permetterebbe a un
  altro di prendere lo stesso nome e a `rename` di sovrascriverlo; non
  bloccano nessuno (chi le trova prese passa a "nome (2)").
- Ogni lavoro gira su un thread suo (`importazione`); quello che parte dopo
  un'automatica annullata aspetta che lei si chiuda (`chiuso`). `esegui`
  prende ogni `Throwable`: prima un `OutOfMemoryError` lasciava il lavoro "in
  corso" per sempre senza thread e senza log. Dopo Annulla il `guardiano`
  chiude il lavoro ancora appeso fuori dai file. Lo stato ha `inLavorazione`
  (file, fase, secondi) e il riquadro lo mostra; `GET /api/diagnostica/thread`
  (admin) dà il dump di tutti i thread, virtuali compresi (DEPLOY.md).
- "Indicizza archivio" salta i file che hanno già una scheda con
  `existsByPercorso` (indice su `percorso`) prima di aprirli: un rilancio non
  scarica niente di quello che è già fatto.
- Cartelle e cartella automatica riconoscono i file con `FotoService.TIPI`:
  entrano anche HEIC e video, e `importa` passa il `Path`, mai i byte.

## Telefoni (pCloud) e archivio di famiglia

- `SincronizzazioneTelefono` (pacchetto `telefono`) copia i file nuovi di ogni
  telefono (`SorgenteTelefono`, collezione `sorgenti_telefono`: nome,
  proprietario, `sorgente` come `pcloud:Automatic Upload` o
  `pcloud-anna:...`, orari, ultimo giro) nella cartella automatica, tutto con
  l'API rc (`Rclone.chiama`), non col montaggio: `operations/list`
  (ricorsivo, solo file), `operations/copyfile`, `operations/stat`
  (`{"item": null}` se manca), `operations/deletefile`. La destinazione è la
  cartella automatica relativa al punto di montaggio (`/cloud/telefono` →
  `lifetime:telefono`), **piatta per tutti i telefoni** (`/` → ` - `): le
  sottocartelle diventerebbero album.
- Copie a metà (lasciate dalla copia sincrona di prima della #43): all'inizio
  di ogni giro `togliCopieAMeta` toglie dalla cartella automatica i file col
  nome di un file della sorgente (`nome.ext` o `nome (N).ext`) ma più piccoli
  di lui, che non sono nel registro e che rclone non sta copiando
  (`core/stats`); poi il giro li ricopia interi. Un file nel registro non si
  tocca mai: così `cancella` non scambia una copia tolta per una importata.
- Registro `copie_telefono` (chiave unica sorgente+percorso+dimensione, più
  `idSorgente` e `proprietario`): un file nel registro non si ricopia mai, anche
  se il telefono viene tolto e rimesso con la stessa cartella. Due telefoni non
  possono avere la stessa cartella né una dentro l'altra (`Sorgente.sovrapposta`). Dopo
  `giorniPrimaDiCancellare` si toglie dalla sorgente (solo le copie di quella
  sorgente) se non è più nella cartella automatica.
- **Video troppo grandi per LifetimeCloud** (oltre 20480 MB "File too large", o 413 di
  nginx): `VideoGrandi` (collezione `video_grandi_telefono`). Il giro non copia i video oltre
  `fototimeline.telefono.video-grandi.massimo-mb` e mette in coda quelli rifiutati per la
  dimensione (`VideoGrandi.rifiutatoPerDimensione`, niente seconda prova): non sono errori. Un
  thread `video-grandi` alla volta: rclone scarica l'originale in `cartella` (sul server
  `/video-grandi`, condivisa con rclone come `/takeout`; spazio controllato prima, se manca
  RIMANDATO), `StrumentiMedia.ricomprimi` (x265, `nice`, `-fs`, `oom_score_adj` 1000), durata
  controllata, CRF più alto se ancora grande, poi nella cartella automatica col nome
  dell'originale e nel registro con `ricompresso=true`. Il giro dopo, **solo se c'è una scheda
  con lo SHA-256 del ricompresso**, l'originale si sposta (`operations/movefile`, nome libero) in
  `cartella-originali` su pCloud, che `copia` salta. `cancella` non tocca mai le copie
  `ricompresso`, e nessun percorso cancella l'originale: tenere così. I lavori lunghi di ffmpeg
  (questa e `ConversioneVideo`) prendono il `TurnoVideo`, uno alla volta; la memoria stimata
  (`FormatoVideo.memoriaX265`) oltre `memoria-massima` è un errore che lo dice.
  `inCopia` (le destinazioni in scrittura) è di `VideoGrandi` e condiviso col giro: un nome si
  prenota lì prima di scriverci.
- **Di chi è un file importato**: `LavoriImportazione` passa all'importazione
  automatica i `ProvenienzaFile` (oggi solo `SincronizzazioneTelefono.caricataDa`):
  si cerca nel registro la copia con quella `destinazione` (la più recente) e
  vale il proprietario attuale del telefono, o quello salvato nella copia se il
  telefono non c'è più. Non creare sottocartelle per telefono né prefissi nel nome.
- Un giro alla volta su un thread `telefono`: "Sincronizza ora" e lo scheduler
  (`fototimeline.telefono.controllo`, PT5M) mettono in coda (`inCoda`); stato
  e contatori sono per telefono. Impostazioni e giro si scrivono con update
  separati; l'ultimo giro con `updateFirst`, così un telefono tolto non torna.
- Migrazione (`migra`, all'avvio): il vecchio documento
  `impostazioni/sincronizzazione-telefono` diventa il telefono `telefono`
  ("Telefono") e si cancella; le voci del registro senza `idSorgente` lo
  prendono. Proprietario: `fototimeline.telefono.proprietario`, o il primo admin
  in `utenti`, o il primo admin che entra (`UtenteRegistrato`).
- Permessi: `/api/telefoni` è aperto a chi ha fatto login; `TelefoniController`
  fa vedere a chi non è admin solo i propri telefoni e gli lascia solo
  "Sincronizza ora" (403 sulle modifiche, 404 sui telefoni degli altri).
- `Foto.caricataDa` è lo username Keycloak (`UtenteCorrente.chi()`): lo
  mettono il caricamento, "Importa cartella" (chi l'ha lanciata) e la cartella
  automatica (il proprietario del telefono). Null per le foto di prima e sul PC.
  Filtro `caricataDa` su foto, timeline e mappa; `GET /api/caricate-da` dà i
  conteggi coi nomi.
- `utenti` (`RegistroUtenti`): ogni login (`RuoliKeycloak`) e ogni `GET /api/io`
  aggiornano username, nome, email, admin, ultimo accesso. `GET /api/utenti` è
  da admin. Niente API admin di Keycloak.

## Google Foto

- Pacchetto `google`. **Takeout** (`ImportazioneTakeout`, admin, thread
  `takeout`): zip da `gdrive:Takeout` scaricati uno alla volta con
  `operations/copyfile` asincrono (`_async` + `job/status`) in `/takeout`
  (volume condiviso fra rclone e app), aperti con `ZipFile` (ZIP64, niente
  estrazione completa), un file alla volta in `FotoService.importa(...,
  MetadatiEsterni)`. Registro `takeout_zip` (nome+dimensione+data, `fatti` per
  ripartire), impostazioni in `impostazioni/google-takeout` (`richiesto` =
  riparte dopo un riavvio). Spazio controllato prima di scaricare.
- L'abbinamento file ↔ JSON è in `SidecarTakeout` (supplemental-metadata
  troncato, nomi a 46–51 caratteri, `(n)` spostato, `-edited`, Live Photo):
  un caso nuovo va anche in `SidecarTakeoutTest`. I dati del JSON valgono solo
  dove l'EXIF manca (`OrigineData.GOOGLE`); i file non si riscrivono.
- **Picker** (`SceltaGoogleFoto`, `ClientGoogle`, niente SDK): OAuth con `state`
  in sessione legato all'utente, solo lo scope `photospicker.mediaitems.readonly`;
  refresh token cifrato AES-GCM in `google_token` (`CifraturaToken`).
  `GoogleProperties` e `TokenGoogle` hanno `toString` senza segreti: un campo
  segreto nuovo non deve finire in un record stampabile né in una risposta.
- `/api/google/takeout/**` è da admin in `ConfigurazioneSicurezza`; il resto di
  `/api/google/**` (callback compreso) basta il login.

## Foto quasi uguali

- Pacchetto `quasiuguali`. `Foto.impronta` è un pHash a 64 bit (`Impronta`,
  Java puro: 32×32 riquadri di luminosità, DCT, 8×8 basse frequenze contro la
  mediana) calcolato dalla **miniatura**, mai dall'originale: si fa in
  `FotoService.anteprime` per foto e HEIC (non i video); se non riesce resta
  null e la riempie "Calcola impronte".
- `CalcoloImpronte` (thread `impronte`, uno alla volta, annullabile) prende le
  foto senza impronta per `_id` crescente a blocchi: idempotente, e una foto
  che non riesce non si ripesca nello stesso giro. `POST /api/quasi-uguali/calcola`
  è da admin; `risolvi` e `ignora` hanno i permessi dell'eliminazione;
  `risolvi` manda nel cestino.
- `Raggruppamento`: niente confronto di tutte le coppie. L'impronta si divide
  in `soglia + 1` bande: per il principio dei cassetti due impronte a distanza
  ≤ soglia hanno una banda identica, quindi si confrontano solo le foto nello
  stesso cassetto; poi union-find. Le raffiche (data EXIF entro
  `finestra-raffica`, 10 s) hanno una soglia più larga (`soglia-raffica`, 12),
  cercata scorrendo le foto in ordine di data. Le ricompressioni passano dalle
  bande, senza guardare la data (WhatsApp la cambia).
- Soglia 6 (`fototimeline.quasi-uguali.soglia`): in `ImprontaTest` le copie
  ridimensionate e ricompresse (anche JPEG al 10%) stanno a 0–6 bit, foto
  diverse a 20 o più. Alzarla costa: con 150.000 impronte casuali i gruppi si fanno in
  ~0,2 s a 6 e ~3 s a 8 (bande più strette, cassetti più pieni).
- `QuasiUguali` tiene i gruppi in cache (si rifanno se cambia il numero di foto
  o dopo 10 minuti); `risolvi` e `ignora` la aggiornano senza ricalcolare.
  "Non sono doppioni" salva il gruppo in `quasi_uguali_ignorati`: le foto di uno
  stesso documento non si uniscono più tra loro (risolvi lo fa per le tenute,
  se più d'una). Le preferite: `tieni` sempre vero e `risolvi` le rifiuta (400).

## Cestino

- `FotoService.elimina` non cancella: mette `Foto.eliminataIl` (indice) e la
  foto è nel cestino. Il file resta dov'è, con miniature e video compatibile;
  `ripristina` toglie il campo, `eliminaPerSempre` (solo dal cestino) cancella
  prima i file e poi la scheda. Tutti e tre vogliono il cloud montato.
- **Ogni query sulle foto esclude il cestino**: `FotoService.criteri` lo fa da
  solo, altrimenti `FotoService.nonNelCestino()` o `.and(FotoService.CESTINO).is(null)`.
  Una ricerca, un conteggio o un'aggregazione nuova su `foto` deve passare da
  lì. Restano su tutte le schede, apposta: `trova` (per id: miniature e file
  della pagina del cestino), `riordinaArchivio`, il backup (il file è ancora
  nel cloud), `senzaMiniatura` della Salute, `existsByPercorso` e `findByHash`
  delle importazioni (così il contenuto nel cestino non rientra).
- Reimportare un contenuto nel cestino: DUPLICATA con `FotoService.NEL_CESTINO`,
  mai un ripristino automatico (cartella automatica, Takeout, Indicizza archivio).
- `cestino/Cestino`: pagina, ripristino ed eliminazione di più foto, "Svuota" e
  la pulizia dopo `fototimeline.cestino.giorni` (`FOTOTIMELINE_CESTINO_GIORNI`,
  30; 0 = mai), ogni ora, solo col cloud montato. `/api/cestino/**` ha i
  permessi dell'eliminazione.
- Eventi: `FotoNelCestino`, `FotoRipristinata`, `FotoEliminata` (solo per
  sempre: toglie il vettore da visione). `QuasiUguali` li ascolta, perché nel
  cestino il numero di schede non cambia e la cache non se ne accorgerebbe.

## Git e CI

- Lavora su un branch e apri una pull request verso `main`; la GitHub Action
  `.github/workflows/test.yml` lancia `./mvnw verify` e `npm run build`.
- Prima di fare push esegui gli stessi due comandi in locale.
- Il rilascio su server2 parte quando il branch `produzione` riceve un push
  (`.github/workflows/rilascio.yml`): fallo solo quando Marco lo chiede.
