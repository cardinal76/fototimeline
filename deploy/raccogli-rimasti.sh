#!/usr/bin/env bash
# Raccoglie in UNA cartella di pCloud (RACCOLTA, predefinita "Video originali troppo grandi") le
# foto e i video rimasti su pCloud dopo foto-da-pcloud.sh pulisci, e li riordina lì dentro per
# data. Quello che FotoTimeline ha già (lo stesso SHA-256 in una sua scheda) non si raccoglie: si
# cancella da pCloud. Gira sul PC (WSL), con rclone e il remote "pcloud:" di foto-da-pcloud.sh;
# P: non serve. LifetimeCloud non si tocca: le schede di FotoTimeline si leggono dall'export del
# DB fatto da confronta-tutto.sh (LAVORO_PCLOUD/confronto/fototimeline-db.tsv).
#
#   raccogli-rimasti.sh anteprima    per ogni cartella in cima a pCloud: foto e video sciolti
#                                    (quanti già in FotoTimeline, che si cancellano, e quanti
#                                    da spostare), zip e altri file, con i GB. Non cambia niente
#   raccogli-rimasti.sh sposta       le foto e i video sciolti: già in FotoTimeline si
#                                    cancellano, gli altri vanno in RACCOLTA/<percorso di prima>
#   raccogli-rimasti.sh zip --prova  per ogni zip: foto e video dentro, già in FotoTimeline, da
#                                    copiare, altri file che restano, se lo zip si cancella.
#                                    Scarica gli zip ma non cambia niente su pCloud
#   raccogli-rimasti.sh zip          gli zip: le foto e i video nuovi in RACCOLTA/dagli-zip/
#                                    <zip senza .zip>/<percorso nello zip>, poi lo zip si rifà
#                                    senza foto e video (restano solo i documenti), o si cancella
#                                    se dentro non resta niente
#   raccogli-rimasti.sh riordina     tutto quello che è in RACCOLTA in RACCOLTA/AAAA/MM/<nome>
#                                    (senza data: RACCOLTA/senza-data/), una copia sola per
#                                    contenuto; quello che FotoTimeline ha già si cancella
#   raccogli-rimasti.sh avvia FASE   la FASE (sposta, zip, riordina) in sottofondo: chiede
#                                    CANCELLA qui, poi si può chiudere il terminale
#   raccogli-rimasti.sh stato        a che punto è (e se gira qualcosa in sottofondo)
#   raccogli-rimasti.sh ferma        ferma quello in sottofondo; un rilancio riprende
#
# Comandi per Marco (dalla cartella del repository), in quest'ordine:
#   ./deploy/confronta-tutto.sh da 4        # l'export delle schede (passo 4), poi "dove"
#   ./deploy/raccogli-rimasti.sh anteprima
#   ./deploy/raccogli-rimasti.sh avvia sposta      (poi: stato)
#   ./deploy/raccogli-rimasti.sh zip --prova
#   ./deploy/raccogli-rimasti.sh avvia zip
#   ./deploy/raccogli-rimasti.sh avvia riordina
#
# Cosa si salta, sempre: RACCOLTA stessa (tranne in riordina), "Automatic Upload" (la cartella
# del telefono: mai toccata), "Crypto Folder", le cartelle di sistema e del cestino, ARCHIVIO
# (quello di foto-da-pcloud.sh, se c'è) e ESCLUDI (percorsi da pCloud, separati da ":").
# pCloud non regge un elenco ricorsivo di tutta la radice: si leggono le cartelle in cima, poi
# una alla volta.
#
# Le cancellazioni: solo un file il cui SHA-256 (quello di pCloud, lato server) è proprio quello
# di una scheda di FotoTimeline, o un doppione identico di un file che resta in RACCOLTA, e solo
# con l'export del DB intero (fototimeline-db.tsv.ok, scritto da confronta-tutto.sh dopo il
# controllo #fine) e di meno di DB_GIORNI giorni (2). Gli originali dei video ricompressi hanno
# un altro SHA-256 di quello della scheda (che è del ricompresso): restano. Prima di cancellare
# lo SHA-256 si richiede a pCloud; un file senza SHA-256 non si cancella mai (si sposta).
# Ogni fase che cancella chiede CANCELLA (SPOSTA se sposta soltanto).
#
# Mai sovrascrivere: un nome già preso in RACCOLTA diventa "nome (2).ext", come fa il server.
# Le cartelle svuotate si tolgono, ma mai quelle in cima a pCloud.
#
# zip, per ogni zip (fuori da RACCOLTA e dalle cartelle saltate), uno alla volta:
#   1. si scarica in LAVORO/tmp, se c'è spazio (tre volte lo zip più 1 GB);
#   2. si estraggono le foto e i video (anche raw, tif, dng, avi, mkv, mts, insv, insp, lrv);
#      le copie "._" dei Mac di una foto o un video si tolgono anche loro;
#   3. quelli nuovi (SHA-256 né in FotoTimeline né già in RACCOLTA) si copiano in
#      RACCOLTA/dagli-zip/... e si controllano: dimensione e SHA-256 di pCloud;
#   4. solo con tutti al sicuro (in FotoTimeline o controllati in RACCOLTA) lo zip si rifà senza
#      foto e video (zip -d sulla copia: gli altri file restano byte per byte com'erano, poi
#      unzip -t e l'elenco delle voci), si carica accanto con il nome "<zip>.raccogli-nuovo", si
#      controlla la dimensione e prende il posto dell'originale (moveto lato server). Se dentro non
#      resta niente, lo zip si cancella;
#   5. uno zip rovinato (unzip non lo legge tutto): si recupera quello che si legge (una voce
#      alla volta, poi zip -FF), le foto nuove si copiano, ma lo zip resta com'è.
#   Ogni passo va in LAVORO/zip-stato.tsv: dopo un'interruzione il rilancio finisce prima di tutto
#   gli "<zip>.raccogli-nuovo" già caricati e controllati, e toglie quelli a metà.
#
# riordina: SHA-256 di tutto RACCOLTA lato server; la data si legge dal file senza scaricarlo
# tutto: sotto INTERO_MB (64) si scarica, sopra si leggono solo il principio e la fine (PEZZO_MB,
# 16) in un file sparso sul PC (exiftool e ffprobe trovano lì l'atomo moov di un video da 7 GB).
# La data: EXIF (DateTimeOriginal, CreateDate, MediaCreateDate), poi ffprobe (creation_time), poi
# il nome (IMG_20190101_..., come DATA_DAL_NOME), poi la data del file. Le date lette si ricordano
# (LAVORO/date.tsv). Quello già in AAAA/MM o in senza-data resta dov'è.
#
# I file di LAVORO (predefinito ~/raccogli-rimasti): impostazioni, elenco.tsv (l'ultimo elenco di
# pCloud), sha-sciolti.tsv (SHA-256 di pCloud, per dimensione, data e percorso), registro.tsv
# (quando, fase, esito, origine, destinazione, sha256, dimensione), zip-stato.tsv, zip-membri.tsv,
# date.tsv, prova-zip.tsv; in sottofondo l'uscita va in LAVORO/FASE.log.
#
# Altre variabili: PCLOUD (pcloud:), RACCOLTA, ARCHIVIO (relativo a pCloud; predefinito quello di
# ~/foto-da-pcloud/impostazioni senza /mnt/p/), ESCLUDI, LAVORO_PCLOUD (~/foto-da-pcloud),
# DB_FT (l'export del DB), DB_GIORNI (2), PARALLELI (4, spostamenti di riordina insieme),
# INTERO_MB, PEZZO_MB, CONFERMA (la parola, senza chiederla: per avvia e i test).
#
# Serve: rclone, unzip, zip, exiftool, sha256sum, flock (ffprobe facoltativo).
set -euo pipefail

SE="$(readlink -f "${BASH_SOURCE[0]}")"
QUI="$(dirname "$SE")"
FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/raccogli-rimasti}"
LAVORO_PCLOUD="${LAVORO_PCLOUD:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato. RADICE, PCLOUD, ARCHIVIO e
# ESCLUDI di foto-da-pcloud.sh (LAVORO_PCLOUD/impostazioni) valgono se qui non ci sono.
DATI_PCLOUD="${PCLOUD:-}" DATI_RACCOLTA="${RACCOLTA:-}" DATI_ARCHIVIO="${ARCHIVIO-__nessuno__}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}"
# Da foto-da-pcloud.sh, in una sottoshell: RADICE (su P:), PCLOUD, ARCHIVIO, ESCLUDI (relativi a RADICE).
leggi_foto_da_pcloud() {
    ( RADICE="" PCLOUD="" ARCHIVIO="" ESCLUDI=""
      if [ -f "$LAVORO_PCLOUD/impostazioni" ]; then . "$LAVORO_PCLOUD/impostazioni" 2>/dev/null || true; fi
      printf '%s\n%s\n%s\n%s\n' "$RADICE" "$PCLOUD" "$ARCHIVIO" "$ESCLUDI" )
}
{ IFS= read -r FP_RADICE; IFS= read -r FP_PCLOUD; IFS= read -r FP_ARCHIVIO; IFS= read -r FP_ESCLUDI; } < <(leggi_foto_da_pcloud) || true
# La radice di foto-da-pcloud.sh rispetto a pCloud: /mnt/p -> "", /mnt/p/Foto -> "Foto/".
FP_SOTTO=""
case "${FP_RADICE%/}/" in /mnt/p/?*) FP_SOTTO="${FP_RADICE#/mnt/p/}"; FP_SOTTO="${FP_SOTTO%/}/" ;; esac
PCLOUD="" RACCOLTA="" ARCHIVIO="__nessuno__" ESCLUDI="" ESCLUDI_PROPRIO=""
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
# Il remote di foto-da-pcloud.sh può essere una sottocartella ("pcloud:Foto"): qui serve tutto pCloud.
[ -n "${FP_PCLOUD:-}" ] && case "$FP_PCLOUD" in *:*) FP_PCLOUD="${FP_PCLOUD%%:*}:" ;; *) FP_PCLOUD="" ;; esac
PCLOUD="${DATI_PCLOUD:-${PCLOUD:-${FP_PCLOUD:-pcloud:}}}"
RACCOLTA="${DATI_RACCOLTA:-${RACCOLTA:-Video originali troppo grandi}}"
# ESCLUDI sulla riga di comando prende il posto di quello di foto-da-pcloud.sh (e si ricorda).
if [ "$DATI_ESCLUDI" != __nessuno__ ]; then ESCLUDI="$DATI_ESCLUDI" ESCLUDI_PROPRIO=si; fi
ESCLUDI_FP=""
if [ -z "$ESCLUDI_PROPRIO" ]; then
    ESCLUDI=""
    IFS=: read -r -a _x <<< "${FP_ESCLUDI:-}" || true
    for _e in "${_x[@]}"; do [ -n "$_e" ] && ESCLUDI_FP+="${ESCLUDI_FP:+:}$FP_SOTTO${_e%/}"; done
    ESCLUDI="$ESCLUDI_FP"
fi
[ "$DATI_ARCHIVIO" != __nessuno__ ] && ARCHIVIO="$DATI_ARCHIVIO"
# Quello di foto-da-pcloud.sh è un percorso su P: (/mnt/p/Archivio foto).
[ "$ARCHIVIO" = __nessuno__ ] && ARCHIVIO="${FP_ARCHIVIO:-}"
ARCHIVIO="${ARCHIVIO#/mnt/p/}"; ARCHIVIO="${ARCHIVIO%/}"
RACCOLTA="${RACCOLTA%/}"; RACCOLTA="${RACCOLTA#/}"
RCLONE="${RCLONE:-rclone}"
DB_FT="${DB_FT:-$LAVORO_PCLOUD/confronto/fototimeline-db.tsv}"
DB_GIORNI="${DB_GIORNI:-2}"
INTERO_MB="${INTERO_MB:-64}"
PEZZO_MB="${PEZZO_MB:-16}"
TEMPO_FF="${TEMPO_FF:-900}"
export TZ="${TZ:-Europe/Rome}"

ELENCO="$LAVORO/elenco.tsv"          # tipo (media|zip|temp|altro) \t dimensione \t data \t percorso
CACHE_S="$LAVORO/sha-sciolti.tsv"    # sha256 \t dimensione \t data \t percorso
SHA_S="$LAVORO/sha-adesso.tsv"       # i media di elenco.tsv con lo sha256 ("-" se pCloud non l'ha dato)
REGISTRO="$LAVORO/registro.tsv"      # quando \t fase \t esito \t origine \t destinazione \t sha256 \t dimensione
ZIP_STATO="$LAVORO/zip-stato.tsv"    # zip \t passo \t dimensione prima \t dimensione dopo \t quando
MEMBRI="$LAVORO/zip-membri.tsv"      # zip \t voce \t sha256 \t esito \t dove in RACCOLTA
DATE="$LAVORO/date.tsv"              # chiave (sha256, o p:percorso:dimensione) \t AAAA/MM|senza-data \t da dove
PROVA_ZIP="$LAVORO/prova-zip.tsv"
FT_SHA="$LAVORO/fototimeline.sha"    # gli sha256 delle schede, uno per riga, ordinati
RACC_ELENCO="$LAVORO/raccolta.tsv"   # dimensione \t data \t percorso in RACCOLTA
RACC_SHA="$LAVORO/raccolta.sha"      # sha256 \t percorso in RACCOLTA
PID="$LAVORO/pid"
TMP="$LAVORO/tmp"

# errore, richiede, rc, impronte (SHA-256 di molti file) ed ESTENSIONI: da lib-foto.sh.
# shellcheck disable=SC2034
ORDINATE="" MANIFEST=/dev/null FONTI=/dev/null DA_CONTROLLARE=/dev/null ARCHIVIATI=/dev/null
# shellcheck disable=SC2034
SPECCHIO="" DOVE_ORIGINE="su pCloud" DOVE_ARCHIVIO="su pCloud" DATA_DAL_NOME="" ZIP_SUL_POSTO="" COPIE_SCARICATE=""
# shellcheck source=lib-foto.sh
. "$QUI/lib-foto.sh"
PARALLELI="${PARALLELI:-4}"
# Le estensioni di raccogli più quelle che l'app non prende: raw, altri video, Insta360.
ESTENSIONI_TUTTE=("${ESTENSIONI[@]}" tif tiff dng raw cr2 cr3 crw nef nrw arw srf sr2 orf rw2 raf srw pef x3f
    avi mkv mts m2ts 3gp 3g2 wmv mpg mpeg webm insv insp lrv)
declare -A E_MEDIA=()
for e in "${ESTENSIONI_TUTTE[@]}"; do E_MEDIA[$e]=1; done
VIDEO=" mp4 m4v mov avi mkv mts m2ts 3gp 3g2 wmv mpg mpeg webm insv lrv "

ora() { date '+%d/%m %H:%M'; }
gb() { awk -v b="$1" 'BEGIN { printf "%.1f GB", b / 1e9 }'; }
# Un percorso dentro un remote: "pcloud:" + "Foto/x.jpg", "/tmp/p" + "Foto/x.jpg".
sotto() { case "$1" in *:) printf '%s%s' "$1" "$2" ;; *) printf '%s/%s' "$1" "$2" ;; esac; }
RACC="$(sotto "$PCLOUD" "$RACCOLTA")"
e_media() {
    local b="${1##*/}" e
    [[ "$b" == *.* ]] && [[ "$b" != ._* ]] || return 1
    e="${b##*.}"
    [ -n "${E_MEDIA[${e,,}]:-}" ]
}
# Una riga di rclone hashsum ("sha256  percorso"), senza {64}: il mawk di Ubuntu 22.04 non lo sa.
AWK_SHA='function e_sha(s) { return length(s) >= 67 && substr(s, 1, 64) !~ /[^0-9a-f]/ && substr(s, 65, 2) == "  " }'
registra() {   # registra FASE ESITO ORIGINE DESTINAZIONE SHA DIMENSIONE
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s)" "$@" >> "$REGISTRO"
}

salva_impostazioni() {
    printf 'PCLOUD=%q\nRACCOLTA=%q\nARCHIVIO=%q\n' "$PCLOUD" "$RACCOLTA" "$ARCHIVIO" > "$IMPOSTAZIONI"
    [ -n "$ESCLUDI_PROPRIO" ] && printf 'ESCLUDI=%q\nESCLUDI_PROPRIO=si\n' "$ESCLUDI" >> "$IMPOSTAZIONI"
    return 0
}
controlla_impostazioni() {
    [ -n "$RACCOLTA" ] || errore "RACCOLTA non può essere vuota (la radice di pCloud)"
    case "/$RACCOLTA/" in
        */../*|*/./*) errore "RACCOLTA ($RACCOLTA) non può avere . o .. dentro" ;;
        "/Automatic Upload/"*) errore "RACCOLTA non può stare in Automatic Upload, la cartella del telefono" ;;
    esac
    if [ -n "$ARCHIVIO" ]; then
        case "$RACCOLTA/" in "$ARCHIVIO"/*) errore "RACCOLTA ($RACCOLTA) non può stare in ARCHIVIO ($ARCHIVIO)" ;; esac
    fi
    [[ "$PARALLELI" =~ ^[1-9][0-9]*$ ]] || errore "PARALLELI dev'essere un numero da 1 in su (è '$PARALLELI')"
    [[ "$INTERO_MB" =~ ^[0-9]+$ ]] && [[ "$PEZZO_MB" =~ ^[1-9][0-9]*$ ]] || errore "INTERO_MB e PEZZO_MB sono numeri di MB"
    mkdir -p "$LAVORO"
}

# conferma PAROLA: la chiede (o la prende da CONFERMA); CANCELLA vale anche per SPOSTA.
conferma() {
    local parola="$1" r="${CONFERMA:-}"
    if [ -z "$r" ]; then read -r -p "Scrivi $parola per confermare: " r || true; fi
    if [ "$r" = "$parola" ] || { [ "$parola" = SPOSTA ] && [ "$r" = CANCELLA ]; }; then return 0; fi
    echo "Non ho fatto niente."
    exit 0
}

# Un giro solo alla volta (anche fra fasi diverse: tutte spostano in RACCOLTA). Il lucchetto passa
# ai figli, anche rclone: finché uno gira, un altro non parte.
un_giro_solo() {
    command -v flock >/dev/null || return 0
    exec 9>"$LAVORO/in-corso.lock"
    flock -n 9 || errore "c'è già un giro in corso: $0 stato (o $0 ferma)"
}

# --- Le schede di FotoTimeline ------------------------------------------------------------
# db_pronto: l'export c'è, è intero (il .ok di confronta-tutto.sh, con lo stesso numero di righe)
# e recente. Se sì, FT_SHA ha gli sha256; se no DB_MOTIVO dice perché.
DB_MOTIVO=""
db_pronto() {
    local n quando righe eta
    [ -f "$DB_FT" ] || { DB_MOTIVO="non c'è $DB_FT"; return 1; }
    [ -f "$DB_FT.ok" ] || { DB_MOTIVO="l'export $DB_FT non è finito bene (manca $DB_FT.ok)"; return 1; }
    IFS=$'\t' read -r n quando < "$DB_FT.ok" || true
    righe="$(wc -l < "$DB_FT")"
    [ "$righe" = "$n" ] || { DB_MOTIVO="l'export $DB_FT è a metà ($righe righe su ${n:-?})"; return 1; }
    eta=$(( $(date +%s) - ${quando:-0} ))
    [ "$eta" -le $((DB_GIORNI * 86400)) ] \
        || { DB_MOTIVO="l'export $DB_FT è di $((eta / 86400)) giorni fa (al massimo DB_GIORNI=$DB_GIORNI)"; return 1; }
    awk -F'\t' 'length($2) == 64 && $2 !~ /[^0-9a-f]/ { print $2 }' "$DB_FT" | LC_ALL=C sort -u > "$FT_SHA"
    DB_MOTIVO=""
    return 0
}
come_rifare_db() {
    echo "Rifallo con:  ./deploy/confronta-tutto.sh da 4   (in sottofondo; ./deploy/confronta-tutto.sh dove dice quando il passo 4 è finito), poi rilancia $0 $FASE"
}
richiedi_db() {
    db_pronto || errore "le schede di FotoTimeline non sono pronte: $DB_MOTIVO. $(come_rifare_db)"
    echo "Schede di FotoTimeline: $(wc -l < "$DB_FT") (export del $(date -d "@$(cut -f2 "$DB_FT.ok")" '+%d/%m/%Y %H:%M'))"
}
declare -A FT=()
carica_ft() { local h; FT=(); while IFS= read -r h; do FT[$h]=1; done < "$FT_SHA"; }

# --- Elenco di pCloud ---------------------------------------------------------------------
# Le cartelle di ESCLUDI, una per riga, senza / in fondo.
escludi_lista() {
    local -a x=()
    local e
    IFS=: read -r -a x <<< "$ESCLUDI" || true
    for e in "${x[@]}"; do [ -n "$e" ] && printf '%s\n' "${e%/}"; done
    return 0
}
# cima_saltata NOME: una cartella in cima a pCloud che non si legge nemmeno (senza badare alle
# maiuscole: saltare una cartella in più non fa danni). In MOTIVO il perché.
SEMPRE=("Automatic Upload" "Crypto Folder" "pCloudSync" "pCloud_lost_and_found" "System Volume Information" '$RECYCLE.BIN')
cima_saltata() {
    local n="${1,,}" x
    MOTIVO=""
    for x in "${SEMPRE[@]}"; do [ "$n" = "${x,,}" ] && { MOTIVO="sempre (cartella di pCloud, del telefono o di sistema)"; [ "$x" = "Automatic Upload" ] && MOTIVO="sempre: la cartella del telefono, non si tocca"; return 0; }; done
    case "$n" in .trash*|.pcloud*) MOTIVO="sempre (cestino o cartella nascosta di pCloud)"; return 0 ;; esac
    [ "$n" = "${RACCOLTA,,}" ] && { MOTIVO="è RACCOLTA (la si riordina con riordina)"; return 0; }
    [ -n "$ARCHIVIO" ] && [ "$n" = "${ARCHIVIO,,}" ] && { MOTIVO="è ARCHIVIO di foto-da-pcloud.sh"; return 0; }
    while IFS= read -r x; do
        [ "$n" = "${x,,}" ] && { MOTIVO="in ESCLUDI$([ -z "$ESCLUDI_PROPRIO" ] && echo " (da $LAVORO_PCLOUD/impostazioni)")"; return 0; }
    done < <(escludi_lista)
    return 1
}
# Le cartelle da saltare anche più in basso (RACCOLTA o ARCHIVIO dentro un'altra, ESCLUDI), con /.
prefissi() {
    printf '%s/\n' "$RACCOLTA"
    [ -n "$ARCHIVIO" ] && printf '%s/\n' "$ARCHIVIO"
    escludi_lista | sed 's|$|/|'
    return 0
}
salto_testo() {
    local e
    e="$(escludi_lista | paste -sd, - | sed 's/,/, /g')"
    printf '%s' "$RACCOLTA, Automatic Upload, Crypto Folder, pCloudSync, pCloud_lost_and_found, cartelle di sistema${ARCHIVIO:+, $ARCHIVIO}${e:+, $e}"
}

elenca() {
    local cime c grezzo="$LAVORO/elenco.grezzo" strani=0 st
    echo "Leggo pCloud ($PCLOUD): le cartelle in cima, poi una alla volta (salto $(salto_testo))…"
    cime="$(rc lsf --dirs-only "$PCLOUD" < /dev/null)" || errore "non riesco a leggere $PCLOUD: rilancia $0 $FASE"
    : > "$LAVORO/saltate.tsv"
    rc lsf --files-only --format stp --separator $'\t' "$PCLOUD" < /dev/null > "$grezzo" \
        || errore "non riesco a leggere i file in cima a $PCLOUD: rilancia $0 $FASE"
    while IFS= read -r c; do
        c="${c%/}"
        [ -n "$c" ] || continue
        if cima_saltata "$c"; then printf '%s\t%s\n' "$c" "$MOTIVO" >> "$LAVORO/saltate.tsv"; continue; fi
        st=0
        rc lsf -R --files-only --format stp --separator $'\t' "$(sotto "$PCLOUD" "$c")" < /dev/null \
            | PRE="$c/" awk -F'\t' -v OFS='\t' '{ if (NF == 3) print $1, $2, ENVIRON["PRE"] $3; else print }' >> "$grezzo" || st=$?
        [ "$st" -eq 0 ] || errore "non riesco a leggere $c su pCloud: rilancia $0 $FASE"
    done <<< "$cime"
    strani="$(awk -F'\t' 'NF != 3' "$grezzo" | wc -l)"
    [ "$strani" -gt 0 ] && echo "Attenzione: $strani file con un tab o un a capo nel nome: li salto."
    SALTA="$(prefissi)" MEDIA=" ${ESTENSIONI_TUTTE[*]} " awk -F'\t' -v OFS='\t' '
        BEGIN { n = split(tolower(ENVIRON["SALTA"]), S, "\n"); med = ENVIRON["MEDIA"] }
        NF == 3 {
            p = $3; lp = tolower(p)
            for (i = 1; i <= n; i++) if (S[i] != "" && substr(lp, 1, length(S[i])) == S[i]) next
            b = p; sub(/.*\//, "", b); e = ""
            if (b ~ /\./) { e = tolower(b); sub(/.*\./, "", e) }
            if (b ~ /\.raccogli-nuovo$/) t = "temp"
            else if (e == "zip") t = "zip"
            else if (e != "" && b !~ /^\._/ && index(med, " " e " ")) t = "media"
            else t = "altro"
            print t, $1, $2, p
        }' "$grezzo" > "$ELENCO"
    rm -f "$grezzo"
    echo "  $(awk -F'\t' '$1 == "media"' "$ELENCO" | wc -l) foto e video sciolti, $(awk -F'\t' '$1 == "zip"' "$ELENCO" | wc -l) zip, $(awk -F'\t' '$1 == "altro"' "$ELENCO" | wc -l) altri file"
}

# SHA-256 lato server degli sciolti che non sono già in sha-sciolti.tsv (stessa dimensione,
# data e percorso), a blocchi di 2000; poi SHA_S: sha256 (o vuoto) \t dimensione \t data \t percorso.
# hashsum LISTA: lo SHA-256 lato server dei file della LISTA (relativi a PCLOUD), "sha  percorso".
hashsum_di() { rc hashsum sha256 --files-from-raw "$1" "${2:-$PCLOUD}" < /dev/null 2> "$LAVORO/hashsum.err" || true; }
hash_sciolti() {
    touch "$CACHE_S"
    awk -F'\t' 'FILENAME == ARGV[1] { c[$2 SUBSEP $3 SUBSEP $4] = 1; next }
        $1 == "media" && !(($2 SUBSEP $3 SUBSEP $4) in c) { print $4 }' "$CACHE_S" "$ELENCO" > "$LAVORO/da-hash.txt"
    local n
    n="$(wc -l < "$LAVORO/da-hash.txt")"
    if [ "$n" -gt 0 ]; then
        echo "Chiedo a pCloud lo SHA-256 di $n foto e video (lato server, niente si scarica)…"
        rm -f "$LAVORO"/da-hash.parte.*
        split -l 2000 -d -a 4 "$LAVORO/da-hash.txt" "$LAVORO/da-hash.parte."
        local p
        for p in "$LAVORO"/da-hash.parte.*; do
            hashsum_di "$p" > "$p.out"
            awk -F'\t' -v OFS='\t' "$AWK_SHA"'
                FILENAME == ARGV[1] { if (e_sha($0)) h[substr($0, 67)] = substr($0, 1, 64); next }
                $1 == "media" && ($4 in h) { print h[$4], $2, $3, $4 }' "$p.out" "$ELENCO" >> "$CACHE_S"
            rm -f "$p" "$p.out"
        done
    fi
    awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { c[$2 SUBSEP $3 SUBSEP $4] = $1; next }
        $1 == "media" { k = $2 SUBSEP $3 SUBSEP $4; print ((k in c) ? c[k] : "-"), $2, $3, $4 }' "$CACHE_S" "$ELENCO" > "$SHA_S"
    rm -f "$LAVORO/da-hash.txt"
}

# Cosa c'è in RACCOLTA (dimensione \t data \t percorso), e con SHA256=si anche gli SHA-256.
elenca_raccolta() {
    local st=0
    rc lsf -R --files-only --format stp --separator $'\t' "$RACC" < /dev/null > "$RACC_ELENCO.nuovo" 2> "$LAVORO/lsf.err" || st=$?
    if [ "$st" -eq 3 ]; then : > "$RACC_ELENCO.nuovo"
    elif [ "$st" -ne 0 ]; then cat "$LAVORO/lsf.err" >&2; errore "non riesco a leggere $RACC: rilancia $0 $FASE"
    fi
    awk -F'\t' 'NF == 3' "$RACC_ELENCO.nuovo" > "$RACC_ELENCO"
    rm -f "$RACC_ELENCO.nuovo"
    [ "${1:-}" = sha ] || return 0
    : > "$RACC_SHA"
    [ -s "$RACC_ELENCO" ] || return 0
    rc hashsum sha256 "$RACC" < /dev/null 2> "$LAVORO/hashsum.err" \
        | awk "$AWK_SHA"' e_sha($0) { print substr($0, 1, 64) "\t" substr($0, 67) }' > "$RACC_SHA" || true
}

# --- anteprima ----------------------------------------------------------------------------
anteprima() {
    richiede "$RCLONE"
    controlla_impostazioni
    salva_impostazioni
    elenca
    if [ -s "$LAVORO/saltate.tsv" ]; then
        echo "Cartelle in cima saltate (non si leggono, non si tocca niente):"
        awk -F'\t' '{ printf "    %-30s %s\n", $1, $2 }' "$LAVORO/saltate.tsv"
    fi
    local x
    x="$( { [ -n "$ARCHIVIO" ] && [[ "$ARCHIVIO" == */* ]] && echo "$ARCHIVIO"; escludi_lista | grep / ; } || true)"
    [ -n "$x" ] && { echo "Saltate anche più in basso:"; printf "%s\n" "$x" | sed "s/^/    /"; }
    hash_sciolti
    local pronto=si
    if db_pronto; then :; else pronto=""; : > "$FT_SHA"; fi
    echo
    echo "Foto e video sciolti (già in FotoTimeline: si cancellano; gli altri vanno in $RACCOLTA), zip e altri file:"
    PRONTO="$pronto" awk -F'\t' '
        FILENAME == ARGV[1] { ft[$1] = 1; next }
        FILENAME == ARGV[2] { sha[$4] = $1; next }
        {
            c = $4; if (c ~ /\//) sub(/\/.*/, "", c); else c = "(file in cima)"
            C[c] = 1
            if ($1 == "media") {
                nm[c]++; bm[c] += $2
                if (sha[$4] != "-" && (sha[$4] in ft)) { nc[c]++; bc[c] += $2 } else { ns[c]++; bs[c] += $2; if (sha[$4] == "-") nh[c]++ }
            } else if ($1 == "zip") { nz[c]++; bz[c] += $2 }
            else if ($1 == "altro") na[c]++
        }
        function g(b) { return sprintf("%.1f", b / 1e9) }
        END {
            ok = ENVIRON["PRONTO"] == "si"
            printf "%7s %8s  %7s %8s  %7s %8s  %5s %8s  %6s  %s\n", "media", "GB", "in FT", "GB", "sposta", "GB", "zip", "GB", "altri", "cartella"
            for (c in C) L[++n] = c
            for (i = 2; i <= n; i++) for (j = i; j > 1 && L[j - 1] > L[j]; j--) { t = L[j]; L[j] = L[j - 1]; L[j - 1] = t }
            for (i = 1; i <= n; i++) {
                c = L[i]
                printf "%7d %8s  %7s %8s  %7d %8s  %5d %8s  %6d  %s\n", nm[c], g(bm[c]), ok ? nc[c] + 0 : "?", ok ? g(bc[c]) : "?", ns[c], g(bs[c]), nz[c], g(bz[c]), na[c], c
                tm += nm[c]; tbm += bm[c]; tc += nc[c]; tbc += bc[c]; ts += ns[c]; tbs += bs[c]; tz += nz[c]; tbz += bz[c]; ta += na[c]; th += nh[c]
            }
            printf "%7d %8s  %7s %8s  %7d %8s  %5d %8s  %6d  %s\n", tm, g(tbm), ok ? tc : "?", ok ? g(tbc) : "?", ts, g(tbs), tz, g(tbz), ta, "TOTALE"
            if (th > 0) printf "%d senza SHA-256 da pCloud: non si cancellano, si spostano.\n", th
            if (ok) printf "In sposta si cancellano %d foto e video già in FotoTimeline (%s GB) e se ne spostano %d (%s GB).\n", tc, g(tbc), ts, g(tbs)
        }' "$FT_SHA" "$SHA_S" "$ELENCO"
    [ -n "$pronto" ] || { echo "Quanti sono già in FotoTimeline non lo so: $DB_MOTIVO."; come_rifare_db; }
    local temp
    temp="$(awk -F'\t' '$1 == "temp"' "$ELENCO" | wc -l)"
    [ "$temp" -gt 0 ] && echo "Zip rifatti di un giro interrotto (.raccogli-nuovo): $temp, li sistema $0 zip."
    date +%s > "$LAVORO/anteprima.ok"
    echo
    echo "Dentro gli zip guarda:  $0 zip --prova   (scarica gli zip, non cambia niente)"
    echo "Le cartelle di documenti da non toccare:  ESCLUDI='Documenti:Lavoro' $0 anteprima"
    echo "Quando torna:  $0 avvia sposta"
}

# --- sposta -------------------------------------------------------------------------------
# numerato PERCORSO N: "cartella/nome (N).ext" (come il server per i video troppo grandi).
numerato() {
    local p="$1" n="$2" dir="" b
    [[ "$p" == */* ]] && dir="${p%/*}/"
    b="${p##*/}"
    if [[ "$b" == ?*.* ]]; then printf '%s%s (%s).%s' "$dir" "${b%.*}" "$n" "${b##*.}"; else printf '%s%s (%s)' "$dir" "$b" "$n"; fi
}
declare -A PRESO=()   # percorsi in RACCOLTA già presi (in minuscolo: pCloud non distingue)
carica_presi() {
    local d p
    PRESO=()
    while IFS=$'\t' read -r d _ p; do [ -n "$p" ] && PRESO[${p,,}]=1; done < "$RACC_ELENCO"
    return 0
}
libero() {   # libero PERCORSO: in LIBERO il primo percorso libero in RACCOLTA, e lo prende
    local p="$1" n=2
    LIBERO="$p"
    while [ -n "${PRESO[${LIBERO,,}]:-}" ]; do LIBERO="$(numerato "$p" "$n")"; n=$((n + 1)); done
    PRESO[${LIBERO,,}]=1
}
# presenti_in REMOTE LISTA: i percorsi della LISTA che ci sono, con la dimensione (un solo lsf).
presenti_in() {
    [ -s "$2" ] || return 0
    rc lsf -R --files-only --files-from-raw "$2" --format sp --separator $'\t' "$1" < /dev/null 2>/dev/null || true
}

# Le cartelle svuotate da una LISTA di percorsi (relativi a pCloud), dal fondo; mai quelle in cima.
togli_cartelle_vuote() {
    local c
    awk '{ while (sub(/\/[^\/]*$/, "")) { if ($0 ~ /\//) print } }' "$1" | LC_ALL=C sort -u \
        | awk '{ print gsub("/", "/"), $0 }' | sort -rn | cut -d' ' -f2- > "$LAVORO/cartelle.txt"
    while IFS= read -r c; do
        [ -n "$c" ] && rc rmdir "$(sotto "$PCLOUD" "$c")" < /dev/null 2>/dev/null || true
    done < "$LAVORO/cartelle.txt"
    rm -f "$LAVORO/cartelle.txt"
}

sposta() {
    richiede "$RCLONE"
    controlla_impostazioni
    salva_impostazioni
    richiedi_db
    carica_ft
    elenca
    hash_sciolti
    elenca_raccolta
    carica_presi
    local -a cancella=() via=() da=() a=() rinominati=()
    local -A SHA_DI=() DIM=()
    local sha dim data rel b_canc=0 b_sp=0 senza=0
    while IFS=$'\t' read -r sha dim data rel; do
        [ -n "$rel" ] || continue
        [ "$sha" = - ] && sha=""
        DIM[$rel]="$dim"; SHA_DI[$rel]="$sha"
        if [ -n "$sha" ] && [ -n "${FT[$sha]:-}" ]; then
            cancella+=("$rel"); b_canc=$((b_canc + dim))
        else
            [ -z "$sha" ] && senza=$((senza + 1))
            libero "$rel"
            da+=("$rel"); a+=("$LIBERO"); b_sp=$((b_sp + dim))
            [ "$LIBERO" != "$rel" ] && rinominati+=("$rel -> $LIBERO")
        fi
    done < "$SHA_S"
    if [ "${#cancella[@]}" -eq 0 ] && [ "${#da[@]}" -eq 0 ]; then
        echo "Niente da fare: nessuna foto o video sciolto fuori da $RACCOLTA."
        date +%s > "$LAVORO/fase-sposta.ok"
        return 0
    fi
    echo "Cancello da pCloud ${#cancella[@]} foto e video già in FotoTimeline ($(gb "$b_canc")), per esempio:"
    printf '    %s\n' "${cancella[@]:0:10}"
    echo "Sposto in $RACCOLTA ${#da[@]} foto e video ($(gb "$b_sp")), ognuno col suo percorso, per esempio:"
    printf '    %s\n' "${da[@]:0:10}"
    [ "$senza" -gt 0 ] && echo "  di cui $senza senza SHA-256 da pCloud (non si sa se sono in FotoTimeline: si spostano)"
    if [ "${#rinominati[@]}" -gt 0 ]; then
        echo "Nome già preso in $RACCOLTA (non si sovrascrive): ${#rinominati[@]}, per esempio:"
        printf '    %s\n' "${rinominati[@]:0:10}"
    fi
    if [ "${#cancella[@]}" -gt 0 ]; then conferma CANCELLA; else conferma SPOSTA; fi

    local lista="$LAVORO/sposta-lista.txt" i
    # 1. Le cancellazioni: lo SHA-256 si richiede adesso, e si cancella solo se è ancora in FotoTimeline.
    local -a canc_ok=()
    if [ "${#cancella[@]}" -gt 0 ]; then
        printf '%s\n' "${cancella[@]}" > "$lista"
        local -A ORA=()
        local riga
        while IFS= read -r riga; do
            [[ ${#riga} -ge 67 ]] && ORA["${riga:66}"]="${riga:0:64}"
        done < <(hashsum_di "$lista")
        for rel in "${cancella[@]}"; do
            sha="${ORA[$rel]:-}"
            if [ -n "$sha" ] && [ -n "${FT[$sha]:-}" ]; then canc_ok+=("$rel")
            else echo "  non cancello $rel: lo SHA-256 di adesso non è in FotoTimeline"; registra sposta "non cancellato" "$rel" "" "$sha" "${DIM[$rel]}"
            fi
        done
        if [ "${#canc_ok[@]}" -gt 0 ]; then
            printf '%s\n' "${canc_ok[@]}" > "$lista"
            echo "Cancello ${#canc_ok[@]} file da $PCLOUD…"
            rc delete "$PCLOUD" --files-from-raw "$lista" --stats-one-line --stats 30s --stats-log-level NOTICE < /dev/null \
                || echo "rclone delete non è finito bene: controllo cosa c'è ancora." >&2
            local -A ANCORA=()
            while IFS=$'\t' read -r dim rel; do [ -n "$rel" ] && ANCORA[$rel]=1; done < <(presenti_in "$PCLOUD" "$lista")
            for rel in "${canc_ok[@]}"; do
                if [ -n "${ANCORA[$rel]:-}" ]; then registra sposta "non cancellato" "$rel" "" "${SHA_DI[$rel]}" "${DIM[$rel]}"
                else registra sposta "cancellato, già in FotoTimeline" "$rel" "" "${SHA_DI[$rel]}" "${DIM[$rel]}"
                fi
            done
        fi
    fi
    # 2. Gli spostamenti col percorso uguale: un solo rclone move lato server, senza sovrascrivere.
    local -a stessi=()
    for ((i = 0; i < ${#da[@]}; i++)); do [ "${da[$i]}" = "${a[$i]}" ] && stessi+=("${da[$i]}"); done
    if [ "${#stessi[@]}" -gt 0 ]; then
        printf '%s\n' "${stessi[@]}" > "$lista"
        echo "Sposto ${#stessi[@]} file in $RACC…"
        rc move "$PCLOUD" "$RACC" --files-from-raw "$lista" --ignore-existing --no-traverse \
            --transfers 8 --stats-one-line --stats 30s --stats-log-level NOTICE < /dev/null \
            || echo "rclone move non è finito bene: controllo cosa si è spostato." >&2
    fi
    # 3. Quelli col nome cambiato, uno alla volta.
    for ((i = 0; i < ${#da[@]}; i++)); do
        [ "${da[$i]}" = "${a[$i]}" ] && continue
        rc moveto "$(sotto "$PCLOUD" "${da[$i]}")" "$(sotto "$RACC" "${a[$i]}")" --ignore-existing < /dev/null \
            || echo "  non riesco a spostare ${da[$i]}" >&2
    done
    # Spostato è solo quello che ora è in RACCOLTA, con la sua dimensione, e non più dov'era.
    if [ "${#da[@]}" -gt 0 ]; then
        printf '%s\n' "${da[@]}" > "$lista"
        local -A RESTA=() ARRIVATO=()
        while IFS=$'\t' read -r dim rel; do [ -n "$rel" ] && RESTA[$rel]=1; done < <(presenti_in "$PCLOUD" "$lista")
        elenca_raccolta
        while IFS=$'\t' read -r dim data rel; do [ -n "$rel" ] && ARRIVATO[$rel]="$dim"; done < "$RACC_ELENCO"
        local male=0
        for ((i = 0; i < ${#da[@]}; i++)); do
            rel="${da[$i]}"
            if [ -z "${RESTA[$rel]:-}" ] && [ "${ARRIVATO[${a[$i]}]:-}" = "${DIM[$rel]}" ]; then
                registra sposta spostato "$rel" "$RACCOLTA/${a[$i]}" "${SHA_DI[$rel]}" "${DIM[$rel]}"
            else
                registra sposta "non spostato" "$rel" "$RACCOLTA/${a[$i]}" "${SHA_DI[$rel]}" "${DIM[$rel]}"
                male=$((male + 1))
            fi
        done
    fi
    { printf '%s\n' "${canc_ok[@]}" "${da[@]}"; } | sed '/^$/d' > "$lista"
    togli_cartelle_vuote "$lista"
    rm -f "$lista"
    riepilogo sposta
    [ "${male:-0}" -eq 0 ] || errore "${male} file non si sono spostati (\"non spostato\" in $REGISTRO): rilancia $0 sposta"
    date +%s > "$LAVORO/fase-sposta.ok"
    echo "Prossimo passo: $0 zip --prova, poi $0 avvia zip"
}

riepilogo() {   # riepilogo FASE: i conti del registro per quella fase
    [ -s "$REGISTRO" ] || return 0
    echo "Registro ($REGISTRO), $1:"
    awk -F'\t' -v f="$1" '$2 == f { n[$3]++; b[$3] += $7 } END { for (e in n) printf "    %7d  %-36s %.1f GB\n", n[e], e, b[e] / 1e9 }' "$REGISTRO" | sort -k2
}

# --- zip ----------------------------------------------------------------------------------
# L'ultimo passo di ogni zip in zip-stato.tsv: PASSO[zip], DIM_PRIMA[zip], DIM_DOPO[zip].
declare -A PASSO=() DIM_PRIMA=() DIM_DOPO=()
carica_zip_stato() {
    local z p d1 d2
    PASSO=() DIM_PRIMA=() DIM_DOPO=()
    touch "$ZIP_STATO"
    while IFS=$'\t' read -r z p d1 d2 _; do
        [ -n "$z" ] || continue
        [ "$d2" = - ] && d2=""
        PASSO[$z]="$p"; DIM_PRIMA[$z]="$d1"; DIM_DOPO[$z]="$d2"
    done < "$ZIP_STATO"
    return 0
}
segna_zip() {   # segna_zip ZIP PASSO DIM_PRIMA DIM_DOPO ("-" se non c'è: read salta i campi vuoti)
    printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:--}" "$(date +%s)" >> "$ZIP_STATO"
    PASSO[$1]="$2"; DIM_PRIMA[$1]="$3"; DIM_DOPO[$1]="$4"
}
# Uno zip già finito, com'è adesso su pCloud (stessa dimensione di quando si è finito)?
zip_finito() {
    local z="$1" dim="$2"
    case "${PASSO[$z]:-}" in
        sostituito) [ "${DIM_DOPO[$z]}" = "$dim" ] ;;
        senza-media|rovinato) [ "${DIM_PRIMA[$z]}" = "$dim" ] ;;
        *) return 1 ;;
    esac
}
dim_su_pcloud() {   # dim_su_pcloud REMOTE_FILE: la dimensione, vuota se non c'è
    rc lsf --files-only --format s "$1" < /dev/null 2>/dev/null | head -1 || true
}

# Gli "<zip>.raccogli-nuovo" di un giro interrotto: caricati e controllati (zip-stato dice
# "caricato" con quella dimensione) prendono il posto dell'originale; gli altri, a metà, si tolgono.
riprendi_temp() {
    local tipo dim data rel z fatto=""
    while IFS=$'\t' read -r tipo dim data rel; do
        [ "$tipo" = temp ] || continue
        z="${rel%.raccogli-nuovo}"
        if [ "${PASSO[$z]:-}" = caricato ] && [ "${DIM_DOPO[$z]}" = "$dim" ]; then
            echo "Ripresa: $rel era caricato e controllato, prende il posto di $z"
            sostituisci "$z" "${DIM_PRIMA[$z]}" "$dim" || true
        else
            echo "Ripresa: $rel è un caricamento a metà, lo tolgo (lo zip si rifà)"
            rc deletefile "$(sotto "$PCLOUD" "$rel")" < /dev/null || errore "non riesco a togliere $rel: rilancia $0 zip"
        fi
        fatto=si
    done < "$ELENCO"
    # Caricato, ma il .raccogli-nuovo non c'è più: lo spostamento era già fatto?
    while IFS=$'\t' read -r tipo dim data rel; do
        [ "$tipo" = zip ] || continue
        if [ "${PASSO[$rel]:-}" = caricato ] && [ "${DIM_DOPO[$rel]}" = "$dim" ]; then
            segna_zip "$rel" sostituito "${DIM_PRIMA[$rel]}" "$dim"
            registra zip "zip rifatto senza foto e video" "$rel" "$rel" "" "$dim"
        fi
    done < "$ELENCO"
    [ -z "$fatto" ] || elenca
}

# sostituisci ZIP DIM_PRIMA DIM_DOPO: il .raccogli-nuovo (già controllato) al posto dello zip.
sostituisci() {
    local z="$1" d1="$2" d2="$3" zr tr ora
    zr="$(sotto "$PCLOUD" "$z")"; tr="$zr.raccogli-nuovo"
    ora="$(dim_su_pcloud "$zr")"
    if [ -n "$ora" ] && [ "$ora" != "$d1" ] && [ "$ora" != "$d2" ]; then
        echo "    $z è cambiato su pCloud ($ora byte invece di $d1): non lo sostituisco, tolgo il nuovo"
        rc deletefile "$tr" < /dev/null || true
        segna_zip "$z" cambiato "$d1" "$d2"
        return 1
    fi
    rc moveto "$tr" "$zr" < /dev/null || { echo "    non riesco a mettere il nuovo al posto di $z: si riprova al prossimo giro" >&2; return 1; }
    if [ "$(dim_su_pcloud "$zr")" = "$d2" ] && [ -z "$(dim_su_pcloud "$tr")" ]; then
        segna_zip "$z" sostituito "$d1" "$d2"
        registra zip "zip rifatto senza foto e video" "$z" "$z" "" "$d2"
        return 0
    fi
    echo "    dopo lo spostamento $z non torna: si riprova al prossimo giro" >&2
    return 1
}

# modello VOCE: la voce come modello di unzip, con [ * ? presi alla lettera.
modello() { printf '%s' "$1" | sed 's/[[*?]/[&]/g'; }
# Le voci di uno zip (solo file) in tre gruppi: MEDIA_V (foto e video), MAC_V (le copie ._ dei
# Mac di una foto o un video, da togliere anche loro), ALTRI_V (quelle che restano).
classifica_voci() {
    local v b
    MEDIA_V=() MAC_V=() ALTRI_V=()
    for v in "${VOCI[@]}"; do
        case "$v" in */|"") continue ;; esac
        b="${v##*/}"
        if [[ "$b" == ._* ]]; then
            if e_media "${b#._}"; then MAC_V+=("$v"); else ALTRI_V+=("$v"); fi
        elif [[ "$v" == __MACOSX/* ]]; then
            ALTRI_V+=("$v")
        elif e_media "$v"; then
            MEDIA_V+=("$v")
        else
            ALTRI_V+=("$v")
        fi
    done
}
# recupera ZIP: le foto e i video di uno zip rovinato in $d/in, una voce alla volta, ognuna solo
# se unzip la controlla (CRC) intera.
recupera() {
    local z="$1" v m
    for v in "${MEDIA_V[@]}"; do
        [ -f "$d/in/$v" ] && continue
        m="$(modello "$v")"
        if unzip -tqq -P nessuna "$z" "$m" < /dev/null > /dev/null 2>&1; then
            unzip -qq -o -P nessuna "$z" "$m" -d "$d/in" < /dev/null > /dev/null 2>&1 || rm -f "$d/in/$v"
        fi
    done
}

# Un zip: scarica, apre, mette al sicuro le foto e i video nuovi, rifà lo zip senza. Con PROVA=si
# solo i conti. Vede FT, RACC_H (sha256 -> percorso in RACCOLTA), PRESO.
declare -A RACC_H=()
uno_zip() {
    local zrel="$1" dim="$2" zr d="$TMP/zip" st=0 libero_b
    zr="$(sotto "$PCLOUD" "$zrel")"
    rm -rf "$d"; mkdir -p "$d/in" "$d/su"
    libero_b="$(df -P -B1 "$TMP" | awk 'NR == 2 { print $4 }')"
    if [ "$libero_b" -lt $((dim * 3 + 1000000000)) ]; then
        echo "    spazio finito sul PC per questo zip ($(gb "$dim")): libera spazio, poi $0 zip"; NON_RIUSCITI=$((NON_RIUSCITI + 1)); return 0
    fi
    if ! rc copyto "$zr" "$d/a.zip" < /dev/null 2> "$d/err"; then
        tail -n 3 "$d/err" >&2
        echo "    non scaricato: si riprova al prossimo giro"; NON_RIUSCITI=$((NON_RIUSCITI + 1)); return 0
    fi
    if [ "$(stat -c %s "$d/a.zip")" != "$dim" ]; then
        echo "    è cambiato su pCloud mentre lo scaricavo: si riprova al prossimo giro"; NON_RIUSCITI=$((NON_RIUSCITI + 1)); return 0
    fi
    unzip -tqq -P nessuna "$d/a.zip" < /dev/null > /dev/null 2>&1 || st=$?
    local rovinato=""
    [ "$st" -gt 1 ] && rovinato=si
    VOCI=()
    mapfile -t VOCI < <(unzip -Z1 "$d/a.zip" < /dev/null 2>/dev/null || true)
    classifica_voci
    if [ -z "$rovinato" ]; then
        local -a quali=()
        local e
        for e in "${ESTENSIONI_TUTTE[@]}"; do quali+=("*.$e"); done
        local su=0
        unzip -qq -o -C -P nessuna "$d/a.zip" "${quali[@]}" -x '__MACOSX/*' '*/._*' '._*' -d "$d/in" < /dev/null > "$d/unzip.txt" 2>&1 || su=$?
        [ "$su" -eq 11 ] && su=0
    else
        [ "${#VOCI[@]}" -gt 0 ] && recupera "$d/a.zip"
        # zip -FF rifà l'indice dalle voci che trova: le voci che mancano ancora (o tutte).
        timeout "$TEMPO_FF" zip -FF "$d/a.zip" --out "$d/ff.zip" <<< y > /dev/null 2>&1 || true
        if [ -f "$d/ff.zip" ]; then
            if [ "${#VOCI[@]}" -eq 0 ]; then mapfile -t VOCI < <(unzip -Z1 "$d/ff.zip" < /dev/null 2>/dev/null || true); classifica_voci; fi
            recupera "$d/ff.zip"
        fi
        rm -f "$d/ff.zip"
    fi
    # Le impronte delle foto e dei video estratti.
    local v f sha
    local -a estratti=()
    for v in "${MEDIA_V[@]}"; do [ -f "$d/in/$v" ] && estratti+=("$d/in/$v"); done
    SHA=()
    [ "${#estratti[@]}" -gt 0 ] && impronte < <(printf '%s\0' "${estratti[@]}")
    local n_ft=0 n_racc=0 n_copia=0 n_non=0 b_copia=0 sicuro=si dest base="dagli-zip/${zrel%.*}"
    local -a copie_v=() copie_d=() copie_s=() copie_b=() righe=()
    local -A QUI=()
    for v in "${MEDIA_V[@]}"; do
        f="$d/in/$v"
        if [ ! -f "$f" ]; then
            n_non=$((n_non + 1)); sicuro=""
            righe+=("$zrel"$'\t'"$v"$'\t\t'"non estratto"$'\t')
            continue
        fi
        sha="${SHA[$f]}"
        if [ -n "${FT[$sha]:-}" ]; then
            n_ft=$((n_ft + 1)); righe+=("$zrel"$'\t'"$v"$'\t'"$sha"$'\t'"già in FotoTimeline"$'\t')
        elif [ -n "${RACC_H[$sha]:-}" ]; then
            n_racc=$((n_racc + 1)); righe+=("$zrel"$'\t'"$v"$'\t'"$sha"$'\t'"già in RACCOLTA"$'\t'"${RACC_H[$sha]}")
        elif [ -n "${QUI[$sha]:-}" ]; then
            n_racc=$((n_racc + 1)); righe+=("$zrel"$'\t'"$v"$'\t'"$sha"$'\t'"doppione nello zip"$'\t'"${QUI[$sha]}")
        else
            n_copia=$((n_copia + 1)); b_copia=$((b_copia + $(stat -c %s "$f")))
            if [ "${PROVA:-}" = si ]; then dest="$base/$v"; else libero "$base/$v"; dest="$LIBERO"; fi
            QUI[$sha]="$dest"
            copie_v+=("$v"); copie_d+=("$dest"); copie_s+=("$sha"); copie_b+=("$(stat -c %s "$f")")
        fi
    done
    local resta="${#ALTRI_V[@]}" esito
    if [ "${PROVA:-}" = si ]; then
        if [ -n "$rovinato" ]; then esito="rovinato, resta com'è"
        elif [ "$n_non" -gt 0 ]; then esito="resta com'è: $n_non foto o video non si estraggono"
        elif [ $(( ${#MEDIA_V[@]} + ${#MAC_V[@]} )) -eq 0 ]; then esito="niente da togliere"
        elif [ "$resta" -eq 0 ]; then esito="si cancella (dentro solo foto e video)"
        else esito="si rifà senza foto e video"
        fi
        echo "    ${#MEDIA_V[@]} foto e video: $n_ft già in FotoTimeline, $n_racc già in $RACCOLTA, $n_copia da copiare ($(gb "$b_copia")); $resta altri file restano. Lo zip: $esito"
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$zrel" "$dim" "${#MEDIA_V[@]}" "$n_ft" "$n_racc" "$n_copia" "$b_copia" "$resta" "$esito" >> "$PROVA_ZIP"
        P_FT=$((P_FT + n_ft)); P_COPIA=$((P_COPIA + n_copia)); P_BCOPIA=$((P_BCOPIA + b_copia))
        case "$esito" in "si cancella"*) P_CANC=$((P_CANC + 1)) ;; "si rifà"*) P_RIFA=$((P_RIFA + 1)) ;; esac
        rm -rf "$d"
        return 0
    fi
    # Le copie in RACCOLTA, poi il controllo: dimensione e SHA-256 di pCloud.
    local i
    if [ "${#copie_v[@]}" -gt 0 ]; then
        for ((i = 0; i < ${#copie_v[@]}; i++)); do
            mkdir -p "$(dirname "$d/su/${copie_d[$i]}")"
            mv -- "$d/in/${copie_v[$i]}" "$d/su/${copie_d[$i]}"
        done
        rc copy "$d/su" "$RACC" --ignore-existing --no-traverse --transfers 8 < /dev/null 2> "$d/err" \
            || { tail -n 3 "$d/err" >&2; echo "    la copia in $RACCOLTA non è finita bene: controllo"; }
        printf '%s\n' "${copie_d[@]}" > "$d/lista"
        local -A DIM_LA=() SHA_LA=()
        local x y riga
        while IFS=$'\t' read -r x y; do [ -n "$y" ] && DIM_LA[$y]="$x"; done < <(presenti_in "$RACC" "$d/lista")
        while IFS= read -r riga; do
            [[ ${#riga} -ge 67 ]] && SHA_LA["${riga:66}"]="${riga:0:64}"
        done < <(hashsum_di "$d/lista" "$RACC")
        for ((i = 0; i < ${#copie_v[@]}; i++)); do
            if [ "${DIM_LA[${copie_d[$i]}]:-}" = "${copie_b[$i]}" ] && [ "${SHA_LA[${copie_d[$i]}]:-}" = "${copie_s[$i]}" ]; then
                righe+=("$zrel"$'\t'"${copie_v[$i]}"$'\t'"${copie_s[$i]}"$'\tcopiato\t'"${copie_d[$i]}")
                RACC_H[${copie_s[$i]}]="${copie_d[$i]}"
                registra zip "copiato da uno zip" "$zrel/${copie_v[$i]}" "$RACCOLTA/${copie_d[$i]}" "${copie_s[$i]}" "${copie_b[$i]}"
            else
                sicuro=""
                righe+=("$zrel"$'\t'"${copie_v[$i]}"$'\t'"${copie_s[$i]}"$'\tcopia non controllata\t'"${copie_d[$i]}")
                echo "    ${copie_v[$i]}: in $RACCOLTA/${copie_d[$i]} non torna (dimensione o SHA-256)"
            fi
        done
    fi
    [ "${#righe[@]}" -gt 0 ] && printf '%s\n' "${righe[@]}" >> "$MEMBRI"
    local conti="${#MEDIA_V[@]} foto e video: $n_ft già in FotoTimeline, $n_racc già in $RACCOLTA, ${#copie_v[@]} copiati; $resta altri file"
    N_FT=$((N_FT + n_ft)); N_COPIATI=$((N_COPIATI + ${#copie_v[@]}))
    if [ -n "$rovinato" ]; then
        echo "    $conti. Zip rovinato (unzip $st), lasciato com'è$([ "$n_non" -gt 0 ] && echo ": $n_non foto o video non si recuperano")"
        segna_zip "$zrel" rovinato "$dim" ""
        registra zip "zip rovinato, lasciato com'è" "$zrel" "" "" "$dim"
        rm -rf "$d"; return 0
    fi
    if [ -z "$sicuro" ]; then
        echo "    $conti. Lo zip resta com'è: $n_non foto o video non si estraggono o una copia non torna (si riprova al prossimo giro)"
        segna_zip "$zrel" "da rifare" "$dim" ""
        NON_RIUSCITI=$((NON_RIUSCITI + 1))
        rm -rf "$d"; return 0
    fi
    if [ $(( ${#MEDIA_V[@]} + ${#MAC_V[@]} )) -eq 0 ]; then
        echo "    nessuna foto o video dentro: resta com'è"
        segna_zip "$zrel" senza-media "$dim" ""
        rm -rf "$d"; return 0
    fi
    segna_zip "$zrel" sicuri "$dim" ""
    local ora
    ora="$(dim_su_pcloud "$zr")"
    if [ "$ora" != "$dim" ]; then
        echo "    $zrel è cambiato su pCloud: si rifà al prossimo giro"; NON_RIUSCITI=$((NON_RIUSCITI + 1)); rm -rf "$d"; return 0
    fi
    if [ "$resta" -eq 0 ]; then
        rc deletefile "$zr" < /dev/null || { echo "    non riesco a cancellarlo: si riprova"; NON_RIUSCITI=$((NON_RIUSCITI + 1)); rm -rf "$d"; return 0; }
        segna_zip "$zrel" cancellato "$dim" 0
        registra zip "zip cancellato, dentro solo foto e video" "$zrel" "" "" "$dim"
        echo "    $conti. Dentro solo foto e video: zip cancellato"
        N_CANCELLATI=$((N_CANCELLATI + 1))
        rm -rf "$d"; return 0
    fi
    # Lo zip senza foto e video: zip -d sulla copia (gli altri file non si ricomprimono).
    cp "$d/a.zip" "$d/nuovo.zip"
    printf '%s\n' "${MEDIA_V[@]}" "${MAC_V[@]}" | zip -q -nw -d "$d/nuovo.zip" -@ < /dev/stdin > "$d/zip.txt" 2>&1 \
        || { cat "$d/zip.txt" >&2; echo "    zip -d non riesce: lo zip resta com'è"; segna_zip "$zrel" "da rifare" "$dim" ""; NON_RIUSCITI=$((NON_RIUSCITI + 1)); rm -rf "$d"; return 0; }
    local tn=0
    unzip -tqq -P nessuna "$d/nuovo.zip" < /dev/null > /dev/null 2>&1 || tn=$?
    if [ "$tn" -gt 1 ] || ! cmp -s <(unzip -Z1 "$d/nuovo.zip" 2>/dev/null | grep -v '/$' | LC_ALL=C sort) \
            <(printf '%s\n' "${ALTRI_V[@]}" | LC_ALL=C sort); then
        echo "    lo zip rifatto non torna (unzip -t o le voci): lo zip resta com'è"
        segna_zip "$zrel" "da rifare" "$dim" ""; NON_RIUSCITI=$((NON_RIUSCITI + 1)); rm -rf "$d"; return 0
    fi
    local d2
    d2="$(stat -c %s "$d/nuovo.zip")"
    if ! rc copyto "$d/nuovo.zip" "$zr.raccogli-nuovo" < /dev/null || [ "$(dim_su_pcloud "$zr.raccogli-nuovo")" != "$d2" ]; then
        echo "    il caricamento dello zip rifatto non torna: si riprova al prossimo giro"
        segna_zip "$zrel" "da rifare" "$dim" ""; NON_RIUSCITI=$((NON_RIUSCITI + 1)); rm -rf "$d"; return 0
    fi
    segna_zip "$zrel" caricato "$dim" "$d2"
    rm -rf "$d"
    if sostituisci "$zrel" "$dim" "$d2"; then
        echo "    $conti: restano, lo zip è rifatto senza foto e video ($(gb "$dim") -> $(gb "$d2"))"
        N_RIFATTI=$((N_RIFATTI + 1))
    else
        NON_RIUSCITI=$((NON_RIUSCITI + 1))
    fi
}

zip_fase() {
    richiede "$RCLONE" unzip zip sha256sum
    controlla_impostazioni
    salva_impostazioni
    local prova="${PROVA:-}"
    richiedi_db
    carica_ft
    carica_zip_stato
    elenca
    [ "$prova" = si ] || riprendi_temp
    elenca_raccolta sha
    carica_presi
    RACC_H=()
    local sha p
    while IFS=$'\t' read -r sha p; do [ -n "$p" ] && RACC_H[$sha]="$p"; done < "$RACC_SHA"
    local -a zips=() dims=()
    local tipo dim data rel b=0 fatti=0
    while IFS=$'\t' read -r tipo dim data rel; do
        [ "$tipo" = zip ] || continue
        if zip_finito "$rel" "$dim"; then fatti=$((fatti + 1)); continue; fi
        zips+=("$rel"); dims+=("$dim"); b=$((b + dim))
    done < "$ELENCO"
    echo "Zip: ${#zips[@]} da fare ($(gb "$b") da scaricare, uno alla volta), $fatti già fatti."
    if [ "${#zips[@]}" -eq 0 ]; then
        [ "$prova" = si ] || date +%s > "$LAVORO/fase-zip.ok"
        riepilogo zip
        return 0
    fi
    rm -rf "$TMP"; mkdir -p "$TMP"
    NON_RIUSCITI=0 N_FT=0 N_COPIATI=0 N_CANCELLATI=0 N_RIFATTI=0 P_FT=0 P_COPIA=0 P_BCOPIA=0 P_CANC=0 P_RIFA=0
    if [ "$prova" = si ]; then
        : > "$PROVA_ZIP"
    else
        echo "Per ogni zip: le foto e i video nuovi in $RACCOLTA/dagli-zip, poi lo zip si rifà senza foto e"
        echo "video (si cancella se non resta altro). Gli zip rovinati restano com'erano. Per esempio:"
        printf '    %s\n' "${zips[@]:0:10}"
        echo "Prima, per vedere cosa succede a ognuno: $0 zip --prova"
        conferma CANCELLA
    fi
    local i
    for ((i = 0; i < ${#zips[@]}; i++)); do
        echo "[zip $((i + 1))/${#zips[@]}] ${zips[$i]} ($(gb "${dims[$i]}"))  $(ora)"
        uno_zip "${zips[$i]}" "${dims[$i]}"
    done
    rm -rf "$TMP"
    if [ "$prova" = si ]; then
        echo
        echo "Prova (niente è cambiato su pCloud; una riga per zip in $PROVA_ZIP):"
        echo "    $P_FT foto e video già in FotoTimeline (si tolgono dagli zip), $P_COPIA da copiare in $RACCOLTA ($(gb "$P_BCOPIA"))"
        echo "    $P_RIFA zip si rifanno senza foto e video, $P_CANC si cancellano (dentro solo foto e video)"
        echo "Per farlo:  $0 avvia zip"
        return 0
    fi
    echo "In questo giro: $N_COPIATI foto e video copiati in $RACCOLTA, $N_FT già in FotoTimeline; $N_RIFATTI zip rifatti, $N_CANCELLATI cancellati."
    elenca_raccolta
    riepilogo zip
    if [ "$NON_RIUSCITI" -gt 0 ]; then
        errore "$NON_RIUSCITI zip non sono finiti (vedi sopra): rilancia $0 zip, quelli fatti non si rifanno"
    fi
    date +%s > "$LAVORO/fase-zip.ok"
    echo "Prossimo passo: $0 avvia riordina"
}

# --- riordina -----------------------------------------------------------------------------
# La data di un file dal suo contenuto: si scarica intero se è piccolo, se no solo principio e
# fine in un file sparso grande come l'originale. Con exiftool (e ffprobe per i video).
anno_ok() {   # anno_ok AAAA/MM
    [[ "$1" =~ ^([0-9]{4})/(0[1-9]|1[0-2])$ ]] || return 1
    local anno="${BASH_REMATCH[1]}"
    [ "$anno" -ge 1900 ] && [ "$anno" != 1904 ] && [ "$anno" -le "$(date +%Y)" ]
}
dal_nome() {   # dal_nome NOME: AAAA/MM dal nome (IMG_20190101_..., 2019-01-01 ...), dal 1990 a oggi
    if [[ "$1" =~ (^|[^0-9])((19|20)[0-9]{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12][0-9]|3[01]) ]] \
        && [ "${BASH_REMATCH[2]}" -ge 1990 ] && [ "${BASH_REMATCH[2]}" -le "$(date +%Y)" ]; then
        printf '%s/%s' "${BASH_REMATCH[2]}" "${BASH_REMATCH[4]}"
    fi
}
# leggi_date: per DA_LEGGERE (percorso in RACCOLTA \t dimensione \t data \t chiave) scrive in date.tsv.
leggi_date() {
    local lista="$1" dir="$TMP/date" k=0 byte=0 intero=$((INTERO_MB * 1048576)) pezzo=$((PEZZO_MB * 1048576)) tot n=0
    tot="$(wc -l < "$lista")"
    [ "$tot" -gt 0 ] || return 0
    echo "Leggo la data di $tot file (sotto $INTERO_MB MB interi, sopra solo $PEZZO_MB MB in testa e in coda)…"
    local -a P=() D=() K=()
    local rel dim data chiave stub r
    rm -rf "$dir"; mkdir -p "$dir"
    while IFS=$'\t' read -r rel dim data chiave; do
        stub="$dir/$k/${rel##*/}"
        mkdir -p "$dir/$k"
        r="$(sotto "$RACC" "$rel")"
        if [ "$dim" -le "$intero" ] || [ "$dim" -le $((pezzo * 2)) ]; then
            rc copyto "$r" "$stub" < /dev/null 2>/dev/null || rm -f "$stub"
            byte=$((byte + dim))
        else
            truncate -s "$dim" "$stub"
            { rc cat --offset 0 --count "$pezzo" "$r" < /dev/null 2>/dev/null | dd of="$stub" conv=notrunc status=none; } || true
            { rc cat --offset $((dim - pezzo)) --count "$pezzo" "$r" < /dev/null 2>/dev/null \
                | dd of="$stub" conv=notrunc oflag=seek_bytes seek=$((dim - pezzo)) status=none; } || true
            byte=$((byte + 2 * pezzo))
        fi
        P[$k]="$rel" D[$k]="$data" K[$k]="$chiave"
        k=$((k + 1))
        if [ "$k" -ge 200 ] || [ "$byte" -ge 2000000000 ]; then
            date_del_blocco; n=$((n + k)); echo "  $n su $tot ($(ora))"
            rm -rf "$dir"; mkdir -p "$dir"; k=0 byte=0; P=() D=() K=()
        fi
    done < "$lista"
    if [ "$k" -gt 0 ]; then date_del_blocco; fi
    rm -rf "$dir"
}
# Vede dir, k, P, D, K di leggi_date.
date_del_blocco() {
    local -A EX=()
    local riga p v1 v2 v3 i v quando fonte nome
    # shellcheck disable=SC2016
    while IFS=$'\t' read -r p v1 v2 v3; do
        i="${p#"$dir"/}"; i="${i%%/*}"
        for v in "$v1" "$v2" "$v3"; do
            if anno_ok "$v"; then EX[$i]="$v"; break; fi
        done
    done < <(exiftool -q -q -r -f -ext '*' -api QuickTimeUTC -api LargeFileSupport=1 -d '%Y/%m' \
        -p '$Directory/$FileName	$DateTimeOriginal	$CreateDate	$MediaCreateDate' "$dir" < /dev/null 2>/dev/null || true)
    for ((i = 0; i < k; i++)); do
        nome="${P[$i]##*/}"
        quando="" fonte=""
        if [ -n "${EX[$i]:-}" ]; then quando="${EX[$i]}" fonte=exif; fi
        if [ -z "$quando" ] && [[ "$VIDEO" == *" ${nome##*.} "* || "$VIDEO" == *" $(printf '%s' "${nome##*.}" | tr '[:upper:]' '[:lower:]') "* ]] \
            && command -v ffprobe >/dev/null && [ -f "$dir/$i/$nome" ]; then
            v="$(ffprobe -v error -show_entries format_tags=creation_time -of default=nw=1:nk=1 "$dir/$i/$nome" < /dev/null 2>/dev/null | head -1 || true)"
            if [ -n "$v" ]; then v="$(date -d "$v" '+%Y/%m' 2>/dev/null || true)"; anno_ok "$v" && quando="$v" fonte=ffprobe; fi
        fi
        if [ -z "$quando" ]; then v="$(dal_nome "$nome")"; [ -n "$v" ] && quando="$v" fonte=nome; fi
        if [ -z "$quando" ]; then
            v="${D[$i]:0:4}/${D[$i]:5:2}"
            if anno_ok "$v" && [ "${D[$i]:0:4}" -ge 1990 ]; then quando="$v" fonte="data del file"; fi
        fi
        [ -n "$quando" ] || { quando=senza-data fonte=niente; }
        printf '%s\t%s\t%s\n' "${K[$i]}" "$quando" "$fonte" >> "$DATE"
    done
}

al_suo_posto() { [[ "$1" =~ ^[0-9]{4}/[0-9]{2}/[^/]+$ ]] || [[ "$1" =~ ^senza-data/[^/]+$ ]]; }

riordina() {
    richiede "$RCLONE" exiftool
    controlla_impostazioni
    salva_impostazioni
    richiedi_db
    carica_ft
    echo "Leggo $RACC e gli SHA-256 (lato server)…"
    elenca_raccolta sha
    local -A SHA_DI=() DIM=() DATA=()
    local -a media=() altri=()
    local sha p dim data
    while IFS=$'\t' read -r sha p; do [ -n "$p" ] && SHA_DI[$p]="$sha"; done < "$RACC_SHA"
    while IFS=$'\t' read -r dim data p; do
        [ -n "$p" ] || continue
        DIM[$p]="$dim"; DATA[$p]="$data"
        if e_media "$p"; then media+=("$p"); else altri+=("$p"); fi
    done < "$RACC_ELENCO"
    echo "In $RACCOLTA: ${#media[@]} foto e video, ${#altri[@]} altri file (restano dove sono)."
    # Già in FotoTimeline: si cancellano. Poi una copia sola per contenuto: resta quella già al
    # suo posto (AAAA/MM, poi senza-data), se no la prima per percorso.
    local -a canc_ft=() canc_dop=() fermi=() muovi=()
    local -A TIENI=() DOP_DI=()
    local b_ft=0 b_dop=0
    while IFS= read -r p; do
        sha="${SHA_DI[$p]:-}"
        if [ -n "$sha" ] && [ -n "${FT[$sha]:-}" ]; then canc_ft+=("$p"); b_ft=$((b_ft + DIM[$p])); continue; fi
        if [ -n "$sha" ] && [ -n "${TIENI[$sha]:-}" ]; then
            canc_dop+=("$p"); DOP_DI[$p]="${TIENI[$sha]}"; b_dop=$((b_dop + DIM[$p])); continue
        fi
        [ -n "$sha" ] && TIENI[$sha]="$p"
        if al_suo_posto "$p"; then fermi+=("$p"); else muovi+=("$p"); fi
    done < <(for p in "${media[@]}"; do
                 if [[ "$p" =~ ^[0-9]{4}/[0-9]{2}/[^/]+$ ]]; then r=0; elif [[ "$p" == senza-data/* ]]; then r=1; else r=2; fi
                 printf '%s\t%s\n' "$r" "$p"
             done | LC_ALL=C sort -t $'\t' -k1,1n -k2 | cut -f2-)
    # Le date che mancano (per sha256, o per percorso e dimensione senza sha256).
    touch "$DATE"
    local -A QUANDO=()
    local c q
    while IFS=$'\t' read -r c q _; do [ -n "$c" ] && QUANDO[$c]="$q"; done < "$DATE"
    : > "$LAVORO/da-leggere.tsv"
    for p in "${muovi[@]}"; do
        c="${SHA_DI[$p]:-p:$p:${DIM[$p]}}"
        [ -n "${QUANDO[$c]:-}" ] || printf '%s\t%s\t%s\t%s\n' "$p" "${DIM[$p]}" "${DATA[$p]}" "$c" >> "$LAVORO/da-leggere.tsv"
    done
    rm -rf "$TMP"; mkdir -p "$TMP"
    leggi_date "$LAVORO/da-leggere.tsv"
    rm -f "$LAVORO/da-leggere.tsv"
    while IFS=$'\t' read -r c q _; do [ -n "$c" ] && QUANDO[$c]="$q"; done < "$DATE"
    # Dove va ognuno: AAAA/MM/nome, nome (2), ... mai sopra un altro.
    PRESO=()
    for p in "${media[@]}" "${altri[@]}"; do PRESO[${p,,}]=1; done
    for p in "${canc_ft[@]}" "${canc_dop[@]}"; do unset "PRESO[${p,,}]"; done
    local -a dest=()
    local senza=0
    for p in "${muovi[@]}"; do
        q="${QUANDO[${SHA_DI[$p]:-p:$p:${DIM[$p]}}]:-senza-data}"
        [ "$q" = senza-data ] && senza=$((senza + 1))
        libero "$q/${p##*/}"
        dest+=("$LIBERO")
    done
    echo "Da cancellare: ${#canc_ft[@]} già in FotoTimeline ($(gb "$b_ft")), ${#canc_dop[@]} doppioni identici di un file che resta ($(gb "$b_dop"))."
    [ "${#canc_ft[@]}" -gt 0 ] && printf '    %s\n' "${canc_ft[@]:0:10}"
    echo "Da spostare in AAAA/MM: ${#muovi[@]} (di cui $senza in senza-data); già al loro posto: ${#fermi[@]}. Per esempio:"
    local i
    for ((i = 0; i < ${#muovi[@]} && i < 10; i++)); do printf '    %s -> %s\n' "${muovi[$i]}" "${dest[$i]}"; done
    if [ $(( ${#canc_ft[@]} + ${#canc_dop[@]} + ${#muovi[@]} )) -eq 0 ]; then
        echo "Niente da fare."
        date +%s > "$LAVORO/fase-riordina.ok"
        return 0
    fi
    if [ $(( ${#canc_ft[@]} + ${#canc_dop[@]} )) -gt 0 ]; then conferma CANCELLA; else conferma SPOSTA; fi

    # Le cancellazioni, con lo SHA-256 richiesto adesso: in FotoTimeline, o uguale a quello del
    # file che resta (che dev'esserci ancora).
    local lista="$LAVORO/riordina-lista.txt"
    local -a via=()
    if [ $(( ${#canc_ft[@]} + ${#canc_dop[@]} )) -gt 0 ]; then
        { printf '%s\n' "${canc_ft[@]}" "${canc_dop[@]}"; for p in "${canc_dop[@]}"; do printf '%s\n' "${DOP_DI[$p]}"; done; } | sed '/^$/d' | LC_ALL=C sort -u > "$lista"
        local -A ORA=()
        local riga
        while IFS= read -r riga; do [[ ${#riga} -ge 67 ]] && ORA["${riga:66}"]="${riga:0:64}"; done < <(hashsum_di "$lista" "$RACC")
        for p in "${canc_ft[@]}"; do
            if [ -n "${ORA[$p]:-}" ] && [ -n "${FT[${ORA[$p]}]:-}" ]; then via+=("$p")
            else registra riordina "non cancellato" "$RACCOLTA/$p" "" "${ORA[$p]:-}" "${DIM[$p]}"; fi
        done
        for p in "${canc_dop[@]}"; do
            if [ -n "${ORA[$p]:-}" ] && [ "${ORA[$p]}" = "${ORA[${DOP_DI[$p]}]:-}" ]; then via+=("$p")
            else registra riordina "non cancellato" "$RACCOLTA/$p" "" "${ORA[$p]:-}" "${DIM[$p]}"; fi
        done
        if [ "${#via[@]}" -gt 0 ]; then
            printf '%s\n' "${via[@]}" > "$lista"
            echo "Cancello ${#via[@]} file da $RACC…"
            rc delete "$RACC" --files-from-raw "$lista" --stats-one-line --stats 30s --stats-log-level NOTICE < /dev/null \
                || echo "rclone delete non è finito bene: controllo." >&2
        fi
    fi
    # Gli spostamenti, PARALLELI alla volta, ognuno lato server e senza sovrascrivere.
    if [ "${#muovi[@]}" -gt 0 ]; then
        echo "Sposto ${#muovi[@]} file, $PARALLELI alla volta…"
        for ((i = 0; i < ${#muovi[@]}; i++)); do printf '%s\t%s\0' "${muovi[$i]}" "${dest[$i]}"; done \
            | xargs -0 -r -n 1 -P "$PARALLELI" "$SE" _sposta_uno || true
    fi
    # Com'è adesso: cancellato è quello che non c'è più, spostato quello arrivato con la sua dimensione.
    elenca_raccolta
    local -A ADESSO=()
    while IFS=$'\t' read -r dim data p; do [ -n "$p" ] && ADESSO[$p]="$dim"; done < "$RACC_ELENCO"
    local male=0
    for p in "${via[@]}"; do
        if [ -n "${ADESSO[$p]:-}" ]; then registra riordina "non cancellato" "$RACCOLTA/$p" "" "${SHA_DI[$p]:-}" "${DIM[$p]}"; male=$((male + 1))
        elif [ -n "${DOP_DI[$p]:-}" ]; then registra riordina "cancellato, doppione" "$RACCOLTA/$p" "$RACCOLTA/${DOP_DI[$p]}" "${SHA_DI[$p]:-}" "${DIM[$p]}"
        else registra riordina "cancellato, già in FotoTimeline" "$RACCOLTA/$p" "" "${SHA_DI[$p]:-}" "${DIM[$p]}"
        fi
    done
    for ((i = 0; i < ${#muovi[@]}; i++)); do
        p="${muovi[$i]}"
        if [ -z "${ADESSO[$p]:-}" ] && [ "${ADESSO[${dest[$i]}]:-}" = "${DIM[$p]}" ]; then
            registra riordina spostato "$RACCOLTA/$p" "$RACCOLTA/${dest[$i]}" "${SHA_DI[$p]:-}" "${DIM[$p]}"
        else
            registra riordina "non spostato" "$RACCOLTA/$p" "$RACCOLTA/${dest[$i]}" "${SHA_DI[$p]:-}" "${DIM[$p]}"; male=$((male + 1))
        fi
    done
    # Le cartelle svuotate dentro RACCOLTA (RACCOLTA stessa no).
    { printf '%s\n' "${via[@]}" "${muovi[@]}"; } | sed '/^$/d' | sed "s|^|$(printf '%s' "$RACCOLTA" | sed 's/[\\|&]/\\&/g')/|" > "$lista"
    togli_cartelle_vuote "$lista"
    rm -f "$lista"
    rm -rf "$TMP"
    riepilogo riordina
    [ "$male" -eq 0 ] || errore "$male file non sono andati (\"non spostato\" o \"non cancellato\" in $REGISTRO): rilancia $0 riordina"
    date +%s > "$LAVORO/fase-riordina.ok"
    echo "Fatto: $RACCOLTA è in AAAA/MM (e senza-data)."
}

# Uno spostamento di riordina (da xargs): "da \t a", relativi a RACCOLTA.
sposta_uno() {
    local da_qui="${1%%$'\t'*}" a_li="${1#*$'\t'}"
    rc moveto "$(sotto "$RACC" "$da_qui")" "$(sotto "$RACC" "$a_li")" --ignore-existing < /dev/null \
        || echo "  non riesco a spostare $da_qui" >&2
}

# --- sottofondo ---------------------------------------------------------------------------
in_corso() {
    local p
    [ -f "$PID" ] || return 1
    p="$(cat "$PID")"
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null || return 1
    p="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null)" || return 1
    [[ "$p" == *"raccogli-rimasti.sh"* ]]
}
avvia() {
    local fase="${1:-}"
    case "$fase" in sposta|zip|riordina) ;; *) errore "uso: $0 avvia sposta|zip|riordina" ;; esac
    controlla_impostazioni
    in_corso && errore "c'è già un giro in corso: $0 stato (o $0 ferma)"
    db_pronto || errore "le schede di FotoTimeline non sono pronte: $DB_MOTIVO. $(come_rifare_db)"
    [ -f "$LAVORO/anteprima.ok" ] || errore "prima: $0 anteprima"
    local r="${CONFERMA:-}"
    if [ -z "$r" ]; then
        echo "$fase in sottofondo: cancella da pCloud quello che FotoTimeline ha già (lo dice $0 anteprima$([ "$fase" = zip ] && echo ", e $0 zip --prova"))."
        read -r -p "Scrivi CANCELLA per farlo partire: " r || true
    fi
    [ "$r" = CANCELLA ] || { echo "Non ho fatto niente."; exit 0; }
    local log="$LAVORO/$fase.log"
    : > "$log"
    CONFERMA=CANCELLA setsid nohup "$SE" "$fase" >> "$log" 2>&1 < /dev/null &
    echo "$!" > "$PID"
    echo "$fase partito in sottofondo."
    echo "Per vedere a che punto è:  $0 stato"
    echo "Per seguirlo:              tail -f $log"
    echo "Per fermarlo:              $0 ferma   (poi $0 avvia $fase riprende)"
}
ferma() {
    if ! in_corso; then echo "Non c'è nessun giro in corso."; return 0; fi
    local p g i
    p="$(cat "$PID")"
    g="$(ps -o pgid= -p "$p" | tr -d ' ')"
    # Il giro ha il suo gruppo (setsid): si fermano anche rclone, unzip e exiftool.
    if [ "$g" = "$p" ]; then kill -TERM -- "-$p" 2>/dev/null || true; else kill -TERM "$p" 2>/dev/null || true; fi
    for ((i = 0; i < 300; i++)); do in_corso || break; sleep 0.1; done
    echo "Fermato. Quello già fatto resta: rilancia la fase e riprende."
}
# Il giro in sottofondo scrive il suo pid (lo stesso di avvia) e lo toglie alla fine.
segna_pid() {
    [ -n "${CONFERMA:-}" ] || return 0
    [ -f "$PID" ] && [ "$(cat "$PID")" = "$$" ] && trap 'rm -f "$PID"' EXIT
    return 0
}

stato() {
    echo "Lavoro:      $LAVORO"
    echo "pCloud:      $PCLOUD"
    echo "Raccolta:    $RACCOLTA"
    echo "Salto:       $(salto_testo)"
    if db_pronto; then echo "FotoTimeline: $(wc -l < "$DB_FT") schede (export del $(date -d "@$(cut -f2 "$DB_FT.ok")" '+%d/%m/%Y %H:%M'))"
    else echo "FotoTimeline: non pronto ($DB_MOTIVO)"; fi
    local f
    for f in anteprima sposta zip riordina; do
        local ok="$LAVORO/fase-$f.ok"
        [ "$f" = anteprima ] && ok="$LAVORO/anteprima.ok"
        if [ -f "$ok" ]; then printf '%-12s %s\n' "$f:" "finito $(data_di "$ok")"; else printf '%-12s %s\n' "$f:" "-"; fi
    done
    if in_corso; then echo "In sottofondo: sta girando (pid $(cat "$PID"))."; else echo "In sottofondo: niente."; fi
    for f in sposta zip riordina; do riepilogo "$f"; done
    if [ -s "$ZIP_STATO" ]; then
        carica_zip_stato
        echo "Zip, all'ultimo passo:"
        for f in "${!PASSO[@]}"; do printf '%s\n' "${PASSO[$f]}"; done | sort | uniq -c | sed 's/^ */    /'
    fi
    local log
    log="$(ls -t "$LAVORO"/*.log 2>/dev/null | head -1 || true)"
    if [ -n "$log" ]; then echo "--- ultime righe di $log"; tail -n 8 "$log"; fi
    return 0
}

case "$FASE" in
    anteprima) un_giro_solo; anteprima ;;
    sposta) un_giro_solo; segna_pid; sposta ;;
    zip)
        case "${2:-}" in
            --prova|prova) PROVA=si ;;
            "") PROVA="" ;;
            *) errore "uso: $0 zip [--prova]" ;;
        esac
        un_giro_solo; segna_pid; zip_fase ;;
    riordina) un_giro_solo; segna_pid; riordina ;;
    avvia) avvia "${2:-}" ;;
    stato) stato ;;
    ferma) ferma ;;
    _sposta_uno) sposta_uno "$2" ;;
    *) errore "fase sconosciuta: $FASE (anteprima, sposta, zip [--prova], riordina, avvia FASE, stato, ferma)" ;;
esac
