#!/usr/bin/env bash
# Porta le foto e i video di pCloud in FotoTimeline e li riordina anche su pCloud.
# Gira sul PC (WSL), con pCloud Drive su P: (sudo mount -t drvfs P: /mnt/p) e rclone
# con il remote "lifetime:" (WebDAV di LifetimeCloud).
#
#   foto-da-pcloud.sh anteprima  senza copiare niente: quante foto, video e zip ci sono
#                                in ogni cartella di RADICE, per scegliere cosa ESCLUDERE
#   foto-da-pcloud.sh scarica    con rclone (remote PCLOUD, di default "pcloud:"), in parallelo:
#                                foto, video e zip di RADICE in LAVORO/specchio, saltando gli
#                                esclusi. Molto più veloce che leggere da P: (consigliato)
#   foto-da-pcloud.sh raccogli   cerca foto e video (da specchio/ se c'è, se no da RADICE),
#                                anche dentro gli zip, e li mette in LAVORO/ordinate/AAAA/MM/GG
#   foto-da-pcloud.sh carica     ordinate/ -> lifetime:FotoTimeline, poi controlla
#   foto-da-pcloud.sh archivia   ordinate/ -> ARCHIVIO/AAAA/AAAA-MM.zip su pCloud, poi controlla
#   foto-da-pcloud.sh pulisci    dopo GIORNI giorni da carica e archivia: cancella da pCloud
#                                gli originali copiati (file, zip, cartelle rimaste vuote)
#   foto-da-pcloud.sh stato      a che punto è
#
# La prima volta (poi si ricordano in LAVORO/impostazioni):
#   RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' foto-da-pcloud.sh anteprima
#   ESCLUDI='TASSE:banche:avvocato' foto-da-pcloud.sh anteprima     # finché torna
#   foto-da-pcloud.sh scarica
#   foto-da-pcloud.sh raccogli
# ESCLUDI: cartelle da saltare, relative a RADICE e separate da ":" (le scansioni
# in JPG sono foto per lo script: le cartelle di documenti vanno escluse). Si
# saltano sempre ARCHIVIO, "Crypto Folder" e le cartelle di sistema di Windows.
# Più radici: un raccogli per radice, prima di carica; i doppioni tra una e
# l'altra entrano una volta sola.
#
# Ogni fase si può rilanciare: riprende da dove era arrivata. Pulisci cancella solo
# gli originali elencati in LAVORO/fonti.tsv come copiati per intero; gli zip con
# dentro anche altri file (documenti, ...) e i file non letti restano dove sono,
# elencati in LAVORO/da-controllare.txt. Dopo carica, nell'app: "Indicizza archivio".
#
# Serve: unzip, zip, exiftool, rclone (sudo apt install unzip zip libimage-exiftool-perl rclone).
set -euo pipefail

FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato.
DATI_RADICE="${RADICE:-}" DATI_ARCHIVIO="${ARCHIVIO:-}" DATI_DESTINAZIONE="${DESTINAZIONE:-}" DATI_GIORNI="${GIORNI:-}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}" DATI_PCLOUD="${PCLOUD:-}"
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
RADICE="${DATI_RADICE:-${RADICE:-}}"
ARCHIVIO="${DATI_ARCHIVIO:-${ARCHIVIO:-}}"
DESTINAZIONE="${DATI_DESTINAZIONE:-${DESTINAZIONE:-lifetime:FotoTimeline}}"
GIORNI="${DATI_GIORNI:-${GIORNI:-7}}"
[ "$DATI_ESCLUDI" != __nessuno__ ] && ESCLUDI="$DATI_ESCLUDI"
ESCLUDI="${ESCLUDI:-}"
PCLOUD="${DATI_PCLOUD:-${PCLOUD:-}}"
RCLONE="${RCLONE:-rclone}"
export TZ="${TZ:-Europe/Rome}"

ORDINATE="$LAVORO/ordinate"
IMPRONTE="$LAVORO/impronte.tsv"          # sha256 \t percorso in ordinate/
MANIFEST="$LAVORO/manifest.tsv"          # fonte \t file nello zip (o vuoto) \t sha256 \t percorso in ordinate/
FONTI="$LAVORO/fonti.tsv"                # file|zip \t fonte \t si|no (si = si può cancellare)
DA_CONTROLLARE="$LAVORO/da-controllare.txt"
ARCHIVIATI="$LAVORO/archiviati.tsv"      # AAAA/MM \t zip su pCloud
RADICI="$LAVORO/radici.txt"              # le cartelle raccolte, una per riga
TMP="$LAVORO/tmp"
SPECCHIO="$LAVORO/specchio"              # copia locale di RADICE fatta da "scarica"
ESTENSIONI=(jpg jpeg png gif bmp webp heic heif mp4 m4v mov)

errore() { echo "Errore: $*" >&2; exit 1; }
richiede() {
    for c in "$@"; do
        command -v "$c" >/dev/null || errore "manca $c (sudo apt install unzip zip libimage-exiftool-perl rclone)"
    done
}
rc() { "$RCLONE" "$@"; }
data_di() { date -d "@$(cat "$1")" '+%d/%m/%Y %H:%M'; }

# Il find delle radici: salta l'archivio, le cartelle escluse e quelle di sistema.
trova() {
    local radice="$1"; shift
    local -a salta=(-path "$ARCHIVIO" -o -name 'Crypto Folder' -o -name 'System Volume Information'
        -o -name '$RECYCLE.BIN' -o -name '.Trash*' -o -name '.pcloud*')
    local x
    local IFS=:
    for x in $ESCLUDI; do
        [ -n "$x" ] && salta+=(-o -path "$radice/${x%/}")
    done
    unset IFS
    find "$radice" \( "${salta[@]}" \) -prune -o "$@" 2>/dev/null
}

estensioni_find=()
for e in "${ESTENSIONI[@]}"; do estensioni_find+=(-o -iname "*.$e"); done
estensioni_exif=()
for e in "${ESTENSIONI[@]}"; do estensioni_exif+=(-ext "$e"); done

# I media di una cartella (dei file sciolti o di uno zip aperto), in ordine di data.
# L'ultima regola che trova la data vince: EXIF, poi data del video, poi data del file.
# Le date vuote (0000, o 1904 nei video) non contano.
ordina_cartella() {
    local da="$1" a="$2"
    exiftool -q -q -r -api QuickTimeUTC -d '%Y/%m/%d' "${estensioni_exif[@]}" \
        "-FileName<$a/\${FileModifyDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "-FileName<$a/\${CreateDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "-FileName<$a/\${DateTimeOriginal;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "$da" </dev/null || true
}

# Sposta i file ordinati di un giro in ordinate/: le copie identiche si tengono una
# volta sola, un nome già preso da un altro file prende un numero. Riempie la
# mappa "dove" (sha256 -> percorso in ordinate/).
declare -A IMPRONTA DOVE
carica_impronte() {
    [ -f "$IMPRONTE" ] || return 0
    while IFS=$'\t' read -r sha rel; do IMPRONTA[$sha]="$rel"; done < "$IMPRONTE"
}
metti_in_ordinate() {
    local giro="$1" f rel sha base est n
    DOVE=()
    while IFS= read -r -d '' f; do
        sha=$(sha256sum "$f" | cut -d' ' -f1)
        if [ -n "${IMPRONTA[$sha]:-}" ]; then
            DOVE[$sha]="${IMPRONTA[$sha]}"
            rm -f "$f"
            continue
        fi
        rel="${f#"$giro"/}"
        if [ -e "$ORDINATE/$rel" ]; then
            base="${rel%.*}"; est="${rel##*.}"; n=2
            while [ -e "$ORDINATE/${base}_$n.$est" ]; do n=$((n + 1)); done
            rel="${base}_$n.$est"
        fi
        mkdir -p "$(dirname "$ORDINATE/$rel")"
        mv "$f" "$ORDINATE/$rel"
        IMPRONTA[$sha]="$rel"
        DOVE[$sha]="$rel"
        printf '%s\t%s\n' "$sha" "$rel" >> "$IMPRONTE"
    done < <(find "$giro" -type f -print0 | sort -z)
}

# ARCHIVIO è una cartella sua: né la radice né una cartella che la contiene.
controlla_archivio() {
    case "$RADICE/" in
        "$ARCHIVIO"/*) errore "ARCHIVIO ($ARCHIVIO) non può essere la radice o contenerla: usa una cartella nuova, per esempio '$RADICE/Archivio foto'" ;;
    esac
}

salva_impostazioni() {
    printf 'RADICE=%q\nARCHIVIO=%q\nESCLUDI=%q\nDESTINAZIONE=%q\nGIORNI=%q\nPCLOUD=%q\n' \
        "$RADICE" "$ARCHIVIO" "$ESCLUDI" "$DESTINAZIONE" "$GIORNI" "$PCLOUD" > "$IMPOSTAZIONI"
}

# Il remote rclone che corrisponde a RADICE: P: intero è "pcloud:", /mnt/p/Foto è "pcloud:Foto".
remote_pcloud() {
    if [ -n "$PCLOUD" ]; then
        printf '%s' "$PCLOUD"
    elif mountpoint -q "$RADICE" 2>/dev/null; then
        printf 'pcloud:'
    else
        printf 'pcloud:%s' "${RADICE#/mnt/p/}"
    fi
}

anteprima() {
    [ -n "$RADICE" ] || errore "manca RADICE: RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' $0 anteprima"
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO, la cartella di pCloud per gli zip ordinati"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    [ -d "$RADICE" ] || errore "$RADICE non c'è (pCloud montato? sudo mount -t drvfs P: /mnt/p)"
    controlla_archivio
    salva_impostazioni
    echo "Cerco in $RADICE (può volerci qualche minuto)…"
    echo "Salto: ${ARCHIVIO#"$RADICE"/}, Crypto Folder, cartelle di sistema${ESCLUDI:+, $ESCLUDI}"
    local f rel prima
    local -A foto video zip
    while IFS= read -r -d '' f; do
        rel="${f#"$RADICE"/}"
        if [[ "$rel" == */* ]]; then prima="${rel%%/*}"; else prima="(file in $RADICE)"; fi
        case "${f,,}" in
            *.zip) zip[$prima]=$(( ${zip[$prima]:-0} + 1 )) ;;
            *.mp4|*.m4v|*.mov) video[$prima]=$(( ${video[$prima]:-0} + 1 )) ;;
            *) foto[$prima]=$(( ${foto[$prima]:-0} + 1 )) ;;
        esac
    done < <(trova "$RADICE" -type f \( -iname '*.zip' "${estensioni_find[@]}" \) -print0)
    local -A tutte
    for prima in "${!foto[@]}" "${!video[@]}" "${!zip[@]}"; do tutte[$prima]=1; done
    printf '%8s %8s %6s  %s\n' foto video zip cartella
    local tf=0 tv=0 tz=0
    while IFS= read -r prima; do
        [ -z "$prima" ] && continue
        printf '%8s %8s %6s  %s\n' "${foto[$prima]:-0}" "${video[$prima]:-0}" "${zip[$prima]:-0}" "$prima"
        tf=$((tf + ${foto[$prima]:-0})); tv=$((tv + ${video[$prima]:-0})); tz=$((tz + ${zip[$prima]:-0}))
    done < <(printf '%s\n' "${!tutte[@]}" | sort)
    printf '%8s %8s %6s  %s\n' "$tf" "$tv" "$tz" "TOTALE"
    date +%s > "$LAVORO/anteprima.ok"
    echo
    echo "Le cartelle di documenti (scansioni, ricevute in JPG) vanno escluse, per esempio:"
    echo "  ESCLUDI='TASSE:banche:avvocato' $0 anteprima"
    echo "Per vedere cosa c'è in una cartella:  find '$RADICE/NOME' -iname '*.jpg' | head"
    echo "Quando torna:  $0 raccogli"
}

scarica() {
    richiede "$RCLONE"
    [ -n "$RADICE" ] || errore "manca RADICE: prima $0 anteprima"
    [ -f "$LAVORO/anteprima.ok" ] || errore "prima: $0 anteprima (per escludere le cartelle di documenti)"
    PCLOUD="$(remote_pcloud)"
    salva_impostazioni
    # Le stesse esclusioni di trova(), come filtri di rclone.
    local filtri="$LAVORO/filtri-scarica.txt" x rel
    {
        rel="${ARCHIVIO#"$RADICE"/}"
        [ "$rel" != "$ARCHIVIO" ] && printf -- '- /%s/**\n' "$rel"
        printf -- '- Crypto Folder/**\n- System Volume Information/**\n- $RECYCLE.BIN/**\n- .Trash*/**\n- .pcloud*/**\n'
        local IFS=:
        for x in $ESCLUDI; do [ -n "$x" ] && printf -- '- /%s/**\n' "${x%/}"; done
        unset IFS
        printf -- '+ *.zip\n'
        for x in "${ESTENSIONI[@]}"; do printf -- '+ *.%s\n' "$x"; done
        printf -- '- *\n'
    } > "$filtri"
    mkdir -p "$SPECCHIO"
    echo "Scarico da $PCLOUD in $SPECCHIO (si può interrompere e rilanciare)…"
    rc copy "$PCLOUD" "$SPECCHIO" --filter-from "$filtri" --ignore-case \
        --transfers 8 --checkers 16 --stats-one-line --stats 30s
    date +%s > "$LAVORO/fase-scarica.ok"
    echo "Scaricato: $(du -sh "$SPECCHIO" | cut -f1). Prossimo passo: $0 raccogli"
}

# Da dove legge raccogli: la copia locale se scarica è finito, se no pCloud Drive.
base_lettura() {
    if [ -f "$LAVORO/fase-scarica.ok" ] && [ -d "$SPECCHIO" ]; then printf '%s' "$SPECCHIO"; else printf '%s' "$RADICE"; fi
}

raccogli() {
    richiede exiftool unzip sha256sum
    [ -n "$RADICE" ] || errore "manca RADICE: prima $0 anteprima"
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO, la cartella di pCloud per gli zip ordinati"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    BASE="$(base_lettura)"
    [ -d "$BASE" ] || errore "$BASE non c'è (pCloud montato? sudo mount -t drvfs P: /mnt/p)"
    if [ ! -f "$LAVORO/anteprima.ok" ]; then
        errore "prima: $0 anteprima (per vedere cosa prenderebbe ed escludere le cartelle di documenti)"
    fi
    controlla_archivio
    [ -f "$LAVORO/fase-archivia.ok" ] && errore "questo giro è già archiviato: per uno nuovo usa un altro LAVORO=..."
    salva_impostazioni
    touch "$RADICI"
    grep -Fxq -- "$RADICE" "$RADICI" || echo "$RADICE" >> "$RADICI"
    rm -f "$LAVORO/fase-carica.ok"
    mkdir -p "$ORDINATE"
    touch "$MANIFEST" "$FONTI" "$IMPRONTE" "$DA_CONTROLLARE"
    carica_impronte
    declare -A FATTA
    while IFS=$'\t' read -r _ fonte _; do FATTA[$fonte]=1; done < "$FONTI"

    echo "Cerco foto, video e zip in $BASE (salto $ARCHIVIO)…"
    # Le fonti si segnano sempre col percorso sotto RADICE, anche lette dalla copia locale.
    local sciolti=() zip=() f
    while IFS= read -r -d '' f; do
        [ -n "${FATTA[$(fonte_di "$f")]:-}" ] && continue
        case "${f,,}" in
            *.zip) zip+=("$f") ;;
            *) sciolti+=("$f") ;;
        esac
    done < <(trova "$BASE" -type f \( -iname '*.zip' "${estensioni_find[@]}" \) -print0 | sort -z)
    echo "${#sciolti[@]} foto e video sciolti e ${#zip[@]} zip da fare"

    # File sciolti, a blocchi: exiftool si avvia una volta per blocco, non per file.
    local i blocco=500
    for ((i = 0; i < ${#sciolti[@]}; i += blocco)); do
        echo "[file $((i + 1))-$((i + blocco < ${#sciolti[@]} ? i + blocco : ${#sciolti[@]}))/${#sciolti[@]}] $(dirname "${sciolti[$i]#"$BASE"/}")/"
        raccogli_sciolti "${sciolti[@]:i:blocco}"
    done

    local n=0
    for f in "${zip[@]}"; do
        n=$((n + 1))
        echo "[zip $n/${#zip[@]}] ${f#"$BASE"/}"
        raccogli_zip "$f"
    done
    rm -rf "$TMP"
    echo "Fatto: $(wc -l < "$IMPRONTE") foto e video diversi in $ORDINATE ($(du -sh "$ORDINATE" | cut -f1))."
    [ -s "$DA_CONTROLLARE" ] && echo "Da controllare (restano su pCloud): $(wc -l < "$DA_CONTROLLARE") righe in $DA_CONTROLLARE"
    echo "Prossimo passo: $0 carica"
}

# Il percorso sotto RADICE di un file letto da BASE (che può essere la copia locale).
fonte_di() { printf '%s/%s' "$RADICE" "${1#"$BASE"/}"; }

# Un blocco di file sciolti: li collega (o copia, da P:), li ordina, segna da dove vengono.
# Dalla copia locale sono collegamenti: niente spazio in più, e ordinate/ li tiene.
raccogli_sciolti() {
    rm -rf "$TMP"; mkdir -p "$TMP/in" "$TMP/ord"
    local -A FONTE_DI
    local f copia sha k=0
    for f in "$@"; do
        k=$((k + 1))
        copia="$TMP/in/$k/$(basename "$f")"
        mkdir -p "$(dirname "$copia")"
        ln "$f" "$copia" 2>/dev/null || cp --preserve=timestamps "$f" "$copia"
        sha=$(sha256sum "$copia" | cut -d' ' -f1)
        FONTE_DI[$f]="$sha"
    done
    ordina_cartella "$TMP/in" "$TMP/ord"
    metti_in_ordinate "$TMP/ord"
    local fonte
    for f in "$@"; do
        sha="${FONTE_DI[$f]}"
        fonte="$(fonte_di "$f")"
        if [ -n "${DOVE[$sha]:-}" ]; then
            printf '%s\t\t%s\t%s\n' "$fonte" "$sha" "${DOVE[$sha]}" >> "$MANIFEST"
            printf 'file\t%s\tsi\n' "$fonte" >> "$FONTI"
        else
            echo "non letto: $fonte" >> "$DA_CONTROLLARE"
            printf 'file\t%s\tno\n' "$fonte" >> "$FONTI"
        fi
    done
}

# Uno zip: lo apre, ne ordina i media; si potrà cancellare solo se dentro non c'era altro.
raccogli_zip() {
    local letto="$1" stato=0 z
    z="$(fonte_di "$letto")"
    rm -rf "$TMP"; mkdir -p "$TMP/zip" "$TMP/in" "$TMP/ord"
    if [ "$BASE" = "$SPECCHIO" ]; then
        unzip -q -o "$letto" -d "$TMP/in" </dev/null || stato=$?
    else
        cp "$letto" "$TMP/zip/a.zip"
        unzip -q -o "$TMP/zip/a.zip" -d "$TMP/in" </dev/null || stato=$?
        rm -f "$TMP/zip/a.zip"
    fi
    if [ "$stato" -gt 1 ]; then
        echo "zip rovinato (unzip $stato): $z" >> "$DA_CONTROLLARE"
        printf 'zip\t%s\tno\n' "$z" >> "$FONTI"
        return
    fi
    # Le copie "._nome" dei Mac non sono foto.
    rm -rf "$TMP/in/__MACOSX"
    find "$TMP/in" -name '._*' -type f -delete
    local -a membri=() shas=()
    local m
    while IFS= read -r -d '' m; do
        membri+=("${m#"$TMP/in"/}")
        shas+=("$(sha256sum "$m" | cut -d' ' -f1)")
    done < <(find "$TMP/in" -type f \( -false "${estensioni_find[@]}" \) -print0 | sort -z)
    ordina_cartella "$TMP/in" "$TMP/ord"
    metti_in_ordinate "$TMP/ord"
    local altri eliminabile=si i
    altri=$(find "$TMP/in" -type f | wc -l)
    for ((i = 0; i < ${#membri[@]}; i++)); do
        if [ -n "${DOVE[${shas[$i]}]:-}" ]; then
            printf '%s\t%s\t%s\t%s\n' "$z" "${membri[$i]}" "${shas[$i]}" "${DOVE[${shas[$i]}]}" >> "$MANIFEST"
        else
            eliminabile=no
        fi
    done
    if [ "$altri" -gt 0 ]; then
        # Quello che exiftool non ha spostato: altri file, o media non letti.
        eliminabile=no
        echo "zip con $altri altri file, resta su pCloud: $z" >> "$DA_CONTROLLARE"
        find "$TMP/in" -type f | sed "s|^$TMP/in/|    |" | head -10 >> "$DA_CONTROLLARE"
    fi
    printf 'zip\t%s\t%s\n' "$z" "$eliminabile" >> "$FONTI"
    echo "    ${#membri[@]} foto e video$([ "$altri" -gt 0 ] && echo ", $altri altri file: lo zip resta su pCloud")"
}

carica() {
    richiede "$RCLONE"
    [ -d "$ORDINATE" ] || errore "prima: $0 raccogli"
    echo "Copio $ORDINATE in $DESTINAZIONE…"
    rc copy "$ORDINATE" "$DESTINAZIONE" --backup-dir "${DESTINAZIONE}-sovrascritte" \
        --transfers 4 --stats-one-line --stats 30s
    echo "Controllo che ci sia tutto…"
    if rc check "$ORDINATE" "$DESTINAZIONE" --one-way --size-only; then
        date +%s > "$LAVORO/fase-carica.ok"
        echo "Caricate e controllate. Ora nell'app: Monta, poi Indicizza archivio."
        echo "Prossimo passo: $0 archivia"
    else
        errore "su $DESTINAZIONE manca qualcosa o è diverso: rilancia $0 carica"
    fi
}

archivia() {
    richiede zip unzip
    [ -d "$ORDINATE" ] || errore "prima: $0 raccogli"
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO in $IMPOSTAZIONI"
    touch "$ARCHIVIATI"
    declare -A FATTO
    while IFS=$'\t' read -r mese _; do FATTO[$mese]=1; done < "$ARCHIVIATI"
    mkdir -p "$TMP"
    local mese anno mm dest n
    while IFS= read -r mese; do
        [ -n "${FATTO[$mese]:-}" ] && continue
        anno="${mese%/*}"; mm="${mese#*/}"
        dest="$ARCHIVIO/$anno/$anno-$mm.zip"
        n=2
        while [ -e "$dest" ]; do dest="$ARCHIVIO/$anno/$anno-$mm ($n).zip"; n=$((n + 1)); done
        echo "[$mese] $(find "$ORDINATE/$mese" -type f | wc -l) file -> ${dest#"$ARCHIVIO"/}"
        rm -f "$TMP/mese.zip"
        # -0: foto e video sono già compressi, zipparli di nuovo costa tempo e non rende.
        (cd "$ORDINATE" && zip -q -r -0 -X "$TMP/mese.zip" "$mese")
        mkdir -p "$(dirname "$dest")"
        cp "$TMP/mese.zip" "$dest"
        if [ "$(stat -c %s "$TMP/mese.zip")" != "$(stat -c %s "$dest")" ] || ! unzip -tq "$dest" >/dev/null; then
            rm -f "$dest"
            errore "lo zip di $mese su pCloud non torna: rilancia $0 archivia"
        fi
        printf '%s\t%s\n' "$mese" "$dest" >> "$ARCHIVIATI"
        rm -f "$TMP/mese.zip"
    done < <(cd "$ORDINATE" && find . -mindepth 2 -maxdepth 2 -type d | sed 's|^\./||' | sort)
    date +%s > "$LAVORO/fase-archivia.ok"
    echo "Archiviati su pCloud in $ARCHIVIO."
    echo "Ultimo passo, tra $GIORNI giorni: $0 pulisci"
}

pulisci() {
    [ -f "$LAVORO/fase-carica.ok" ] || errore "prima: $0 carica (e che finisca con il controllo a posto)"
    [ -f "$LAVORO/fase-archivia.ok" ] || errore "prima: $0 archivia"
    local ultimo adesso mancano
    ultimo=$(cat "$LAVORO/fase-carica.ok" "$LAVORO/fase-archivia.ok" | sort -n | tail -1)
    adesso=$(date +%s)
    mancano=$(( (ultimo + GIORNI * 86400 - adesso + 86399) / 86400 ))
    if [ "$mancano" -gt 0 ]; then
        echo "Troppo presto: mancano $mancano giorni (si può dal $(date -d "@$((ultimo + GIORNI * 86400))" '+%d/%m/%Y %H:%M'))."
        echo "Intanto guarda la timeline nell'app e gli zip in $ARCHIVIO."
        exit 0
    fi
    # Con scarica le cancellazioni passano da rclone (molto più veloce che da P:).
    local con_rclone=""
    [ -f "$LAVORO/fase-scarica.ok" ] && [ -n "$PCLOUD" ] && con_rclone=1
    local -a via=()
    local tipo fonte ok
    while IFS=$'\t' read -r tipo fonte ok; do
        [ "$ok" = si ] || continue
        if [ -n "$con_rclone" ] || [ -e "$fonte" ]; then via+=("$fonte"); fi
    done < "$FONTI"
    if [ "${#via[@]}" -eq 0 ]; then
        echo "Niente da cancellare."
        exit 0
    fi
    echo "Cancello da pCloud ${#via[@]} originali già in FotoTimeline e in $ARCHIVIO, per esempio:"
    printf '    %s\n' "${via[@]:0:10}"
    [ -s "$DA_CONTROLLARE" ] && echo "(restano su pCloud quelli in $DA_CONTROLLARE)"
    read -r -p "Scrivi CANCELLA per confermare: " risposta
    [ "$risposta" = CANCELLA ] || { echo "Non ho cancellato niente."; exit 0; }
    local f
    if [ -n "$con_rclone" ]; then
        pulisci_con_rclone "${via[@]}"
        return
    fi
    for f in "${via[@]}"; do rm -f -- "$f"; done
    # Solo le cartelle svuotate da qui, risalendo; mai una radice, e se la radice è
    # tutto pCloud nemmeno le cartelle in cima (Automatic Upload, My Pictures, ...).
    local c radice in_cima
    for f in "${via[@]}"; do
        c="$(dirname "$f")"
        while :; do
            in_cima=""
            while IFS= read -r radice; do
                if [ "$c" = "$radice" ] || [ "$c" = "/" ] || [ "$c" = "." ] \
                    || { mountpoint -q "$radice" 2>/dev/null && [ "$(dirname "$c")" = "$radice" ]; }; then
                    in_cima=1
                fi
            done < "$RADICI"
            [ -n "$in_cima" ] && break
            rmdir "$c" 2>/dev/null || break
            c="$(dirname "$c")"
        done
    done
    date +%s > "$LAVORO/fase-pulisci.ok"
    echo "Fatto. La copia di lavoro sul PC non serve più: rm -rf '$ORDINATE'"
}

# Il percorso rclone di una fonte (/mnt/p/Backups/x.jpg -> pcloud:Backups/x.jpg).
remoto_di() {
    local rel="${1#"$RADICE"/}"
    case "$PCLOUD" in *:) printf '%s%s' "$PCLOUD" "$rel" ;; *) printf '%s/%s' "$PCLOUD" "$rel" ;; esac
}

pulisci_con_rclone() {
    local lista="$LAVORO/da-cancellare.txt" f c
    for f in "$@"; do printf '%s\n' "${f#"$RADICE"/}"; done > "$lista"
    echo "Cancello $(wc -l < "$lista") file da $PCLOUD…"
    rc delete "$PCLOUD" --files-from-raw "$lista" --stats-one-line --stats 30s
    # Le cartelle svuotate, dal fondo: rmdir fallisce (e si salta) se non sono vuote.
    # Mai la radice né, con tutto pCloud come radice, le cartelle in cima.
    local -A cartelle
    for f in "$@"; do
        c="$(dirname "$f")"
        while [ "$c" != "$RADICE" ] && [ "$c" != / ] && [ "$c" != . ]; do
            if mountpoint -q "$RADICE" 2>/dev/null && [ "$(dirname "$c")" = "$RADICE" ]; then break; fi
            cartelle[$c]=1
            c="$(dirname "$c")"
        done
    done
    while IFS= read -r c; do
        [ -n "$c" ] && rc rmdir "$(remoto_di "$c")" 2>/dev/null || true
    done < <(printf '%s\n' "${!cartelle[@]}" | awk '{ print gsub("/", "/"), $0 }' | sort -rn | cut -d' ' -f2-)
    date +%s > "$LAVORO/fase-pulisci.ok"
    echo "Fatto. Le copie di lavoro sul PC non servono più: rm -rf '$ORDINATE' '$SPECCHIO'"
}

stato() {
    echo "Lavoro:      $LAVORO"
    if [ -s "$RADICI" ]; then
        sed '1s/^/Radici:      /; 2,$s/^/             /' "$RADICI"
    else
        echo "Radici:      (non ancora impostate)"
    fi
    echo "Archivio:    ${ARCHIVIO:-(non ancora impostato)}"
    echo "Destinaz.:   $DESTINAZIONE"
    if [ -f "$FONTI" ]; then
        echo "Raccolti:    $(wc -l < "$IMPRONTE") foto e video diversi da $(wc -l < "$FONTI") originali" \
            "($(grep -c $'\tno$' "$FONTI" || true) restano su pCloud)"
    else
        echo "Raccolti:    -"
    fi
    [ -f "$LAVORO/fase-scarica.ok" ] && echo "Scaricati:   $(data_di "$LAVORO/fase-scarica.ok") in $SPECCHIO" || echo "Scaricati:   - (consigliato: $0 scarica)"
    [ -f "$LAVORO/fase-carica.ok" ] && echo "Caricati:    $(data_di "$LAVORO/fase-carica.ok")" || echo "Caricati:    -"
    [ -f "$LAVORO/fase-archivia.ok" ] && echo "Archiviati:  $(data_di "$LAVORO/fase-archivia.ok")" || echo "Archiviati:  -"
    [ -f "$LAVORO/fase-pulisci.ok" ] && echo "Puliti:      $(data_di "$LAVORO/fase-pulisci.ok")" || echo "Puliti:      -"
}

case "$FASE" in
    anteprima) anteprima ;;
    scarica) scarica ;;
    raccogli) raccogli ;;
    carica) carica ;;
    archivia) archivia ;;
    pulisci) pulisci ;;
    stato) stato ;;
    *) errore "fase sconosciuta: $FASE (anteprima, scarica, raccogli, carica, archivia, pulisci, stato)" ;;
esac
