# shellcheck shell=bash disable=SC2153
# Le parti comuni di foto-da-pcloud.sh e foto-da-cartella.sh: raccogliere foto e video
# (anche dagli zip) in LAVORO/ordinate/AAAA/MM/GG senza doppioni, caricarli e archiviarli.
# Non si lancia da solo: lo legge lo script con ". lib-foto.sh".
#
# Lo script che la legge imposta prima:
#   LAVORO ORDINATE IMPRONTE MANIFEST FONTI DA_CONTROLLARE ARCHIVIATI TMP SPECCHIO
#   RADICE ARCHIVIO DESTINAZIONE RCLONE
#   DOVE_ORIGINE   dove restano i file non presi, nei messaggi ("su pCloud")
#   DOVE_ARCHIVIO  dove vanno gli zip di archivia, nei messaggi ("su pCloud")
#   DATA_DAL_NOME  "si": senza EXIF la data si prende dal nome (IMG_20190101_...) prima che
#                  dalla data del file; vuoto: come sempre, EXIF e poi data del file
#   ZIP_SUL_POSTO  "si": gli zip si aprono dove sono, senza copiarli prima in TMP
#   COPIE_SCARICATE "si": " (1)" prima dell'estensione è il doppione di un download e si
#                  toglie dal nome; i file identici di un blocco si ordinano una volta sola
#   SCARTA_NEI_ZIP nomi (anche con *) da buttare dagli zip aperti prima di contarne gli
#                  "altri file": Thumbs.db, desktop.ini, ...
# e definisce trova RADICE [condizioni di find...], il find che salta le cartelle escluse.

ESTENSIONI=(jpg jpeg png gif bmp webp heic heif mp4 m4v mov)

errore() { echo "Errore: $*" >&2; exit 1; }
richiede() {
    for c in "$@"; do
        command -v "$c" >/dev/null || errore "manca $c (sudo apt install unzip zip libimage-exiftool-perl rclone)"
    done
}
rc() { "$RCLONE" "$@"; }
data_di() { date -d "@$(cat "$1")" '+%d/%m/%Y %H:%M'; }

estensioni_find=()
for e in "${ESTENSIONI[@]}"; do estensioni_find+=(-o -iname "*.$e"); done
estensioni_exif=()
for e in "${ESTENSIONI[@]}"; do estensioni_exif+=(-ext "$e"); done

# I media di una cartella (dei file sciolti o di uno zip aperto), in ordine di data.
# L'ultima regola che trova la data vince: EXIF, poi data del video, poi (con DATA_DAL_NOME)
# la data nel nome del file, poi data del file. Le date vuote (0000, o 1904 nei video) non
# contano, e nel nome solo quelle dal 1990 a oggi.
ordina_cartella() {
    local da="$1" a="$2"
    local -a dal_nome=()
    if [ "$DATA_DAL_NOME" = si ]; then
        # IMG_20190101_123456, IMG-20190101-WA0001, PXL_20210911_..., Screenshot 2019-01-01 at ...
        # (19|20)AAAA, poi MM e GG, separati o no da - _ . ; prima non ci va una cifra.
        # shellcheck disable=SC2016
        dal_nome=('-FileName<'"$a"'/${FileName;$_=(/(?:^|\D)((?:19|20)\d\d)[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])/ and $1>=1990 and $1<='"$(date +%Y)"') ? "$1/$2/$3" : undef}/%f%-c.%e')
    fi
    exiftool -q -q -r -api QuickTimeUTC -d '%Y/%m/%d' "${estensioni_exif[@]}" \
        "-FileName<$a/\${FileModifyDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "${dal_nome[@]}" \
        "-FileName<$a/\${CreateDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "-FileName<$a/\${DateTimeOriginal;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "$da" </dev/null || true
}

# Sposta i file ordinati di un giro in ordinate/: le copie identiche si tengono una
# volta sola, un nome già preso da un altro file prende un numero. Riempie la
# mappa "dove" (sha256 -> percorso in ordinate/).
# PRESO: i percorsi già usati da un altro giro (un altro LAVORO), da non riusare.
declare -A IMPRONTA DOVE PRESO
carica_impronte() {
    [ -f "$IMPRONTE" ] || return 0
    while IFS=$'\t' read -r sha rel; do IMPRONTA[$sha]="$rel"; done < "$IMPRONTE"
}
# Le impronte di un altro LAVORO (solo lettura): i suoi doppioni non si riprendono e i
# suoi nomi in ordinate/ non si riusano, perché finiscono nella stessa DESTINAZIONE.
carica_impronte_di() {
    local file="$1" sha rel
    [ -f "$file" ] || return 0
    while IFS=$'\t' read -r sha rel; do
        [ -n "$sha" ] || continue
        IMPRONTA[$sha]="$rel"
        PRESO[$rel]=1
    done < "$file"
}
preso() { [ -e "$ORDINATE/$1" ] || [ -n "${PRESO[$1]:-}" ]; }

# Le impronte di molti file con pochi processi (non un sha256sum per file: su WSL
# ogni processo costa): legge i percorsi separati da NUL e riempie SHA[percorso].
declare -A SHA=()
impronte() {
    local riga
    SHA=()
    while IFS= read -r -d '' riga; do
        SHA["${riga#*  }"]="${riga%%  *}"
    done < <(xargs -0 -r sha256sum -z)
}

# metti_in_ordinate GIRO [IMPRONTE_DEL_GIRO]: il secondo, se c'è, è l'uscita di
# impronte_di_cartella GIRO già calcolata (dalla preparazione in parallelo).
metti_in_ordinate() {
    local giro="$1" gia="${2:-}" f rel sha base est n riga
    local -a file=()
    DOVE=()
    if [ -n "$gia" ]; then
        SHA=()
        while IFS= read -r -d '' riga; do
            f="${riga#*  }"
            file+=("$f")
            SHA["$f"]="${riga%%  *}"
        done < "$gia"
    else
        while IFS= read -r -d '' f; do file+=("$f"); done < <(find "$giro" -type f -print0 | sort -z)
        [ "${#file[@]}" -gt 0 ] && impronte < <(printf '%s\0' "${file[@]}")
    fi
    for f in "${file[@]}"; do
        fermato
        sha="${SHA[$f]}"
        if [ -n "${IMPRONTA[$sha]:-}" ]; then
            DOVE[$sha]="${IMPRONTA[$sha]}"
            rm -f "$f"
            continue
        fi
        rel="${f#"$giro"/}"
        if preso "$rel"; then
            base="${rel%.*}"; est="${rel##*.}"; n=2
            while preso "${base}_$n.$est"; do n=$((n + 1)); done
            rel="${base}_$n.$est"
        fi
        mkdir -p "$(dirname "$ORDINATE/$rel")"
        mv "$f" "$ORDINATE/$rel"
        IMPRONTA[$sha]="$rel"
        DOVE[$sha]="$rel"
        printf '%s\t%s\n' "$sha" "$rel" >> "$IMPRONTE"
    done
}

# Quante foto, video e zip ci sono in ogni cartella in cima a $1 (per anteprima).
conta_media() {
    local radice="$1" f rel prima
    local -A nfoto nvideo nzip
    while IFS= read -r -d '' f; do
        rel="${f#"$radice"/}"
        if [[ "$rel" == */* ]]; then prima="${rel%%/*}"; else prima="(file in $radice)"; fi
        case "${f,,}" in
            *.zip) nzip[$prima]=$(( ${nzip[$prima]:-0} + 1 )) ;;
            *.mp4|*.m4v|*.mov) nvideo[$prima]=$(( ${nvideo[$prima]:-0} + 1 )) ;;
            *) nfoto[$prima]=$(( ${nfoto[$prima]:-0} + 1 )) ;;
        esac
    done < <(trova "$radice" -type f \( -iname '*.zip' "${estensioni_find[@]}" \) -print0)
    local -A tutte
    for prima in "${!nfoto[@]}" "${!nvideo[@]}" "${!nzip[@]}"; do tutte[$prima]=1; done
    printf '%8s %8s %6s  %s\n' foto video zip cartella
    local tf=0 tv=0 tz=0
    while IFS= read -r prima; do
        [ -z "$prima" ] && continue
        printf '%8s %8s %6s  %s\n' "${nfoto[$prima]:-0}" "${nvideo[$prima]:-0}" "${nzip[$prima]:-0}" "$prima"
        tf=$((tf + ${nfoto[$prima]:-0})); tv=$((tv + ${nvideo[$prima]:-0})); tz=$((tz + ${nzip[$prima]:-0}))
    done < <(printf '%s\n' "${!tutte[@]}" | sort)
    printf '%8s %8s %6s  %s\n' "$tf" "$tv" "$tz" "TOTALE"
}

# Il cuore di raccogli: tutte le foto, i video e gli zip di BASE non ancora fatti.
# Prima: BASE e RADICE impostati, ordinate/ creata, le altre impronte già caricate.
raccogli_fonti() {
    [[ "$PARALLELI" =~ ^[1-9][0-9]*$ ]] || errore "PARALLELI dev'essere un numero da 1 in su (è '$PARALLELI')"
    touch "$MANIFEST" "$FONTI" "$IMPRONTE" "$DA_CONTROLLARE"
    # Quello che un giro interrotto ha lasciato a metà in TMP non serve: si rifà da capo.
    # (|| true: se un giro ucciso con kill -9 scrive ancora lì, la sua cartella resta, ma
    # i nomi delle cartelle di questo giro sono diversi.)
    rm -rf "$TMP" 2>/dev/null || true
    carica_impronte
    carica_nomi_lunghi
    declare -A FATTA
    while IFS=$'\t' read -r _ fonte _; do FATTA[$fonte]=1; done < "$FONTI"

    # Le fonti si segnano sempre col percorso sotto RADICE, anche lette dalla copia locale.
    local sciolti=() zip=() f
    while IFS= read -r -d '' f; do
        fonte_in "$f"
        [ -n "${FATTA[$FONTE]:-}" ] && continue
        case "${f,,}" in
            *.zip) zip+=("$f") ;;
            *) sciolti+=("$f") ;;
        esac
    done < <(trova "$BASE" -type f \( -iname '*.zip' "${estensioni_find[@]}" \) -print0 | sort -z)
    echo "${#sciolti[@]} foto e video sciolti e ${#zip[@]} zip da fare"

    # File sciolti, a blocchi: exiftool si avvia una volta per blocco, non per file.
    local blocco=500
    in_fila $(( (${#sciolti[@]} + blocco - 1) / blocco )) titolo_sciolti prepara_sciolti applica_sciolti
    in_fila "${#zip[@]}" titolo_zip prepara_zip applica_zip
    rm -rf "$TMP"
    echo "Fatto: $(wc -l < "$IMPRONTE") foto e video diversi in $ORDINATE ($(du -sh "$ORDINATE" | cut -f1))."
    [ -s "$DA_CONTROLLARE" ] && echo "Da controllare (restano $DOVE_ORIGINE): $(wc -l < "$DA_CONTROLLARE") righe in $DA_CONTROLLARE"
    echo "Prossimo passo: $0 carica"
}

# --- I lavori di raccogli (blocchi di file sciolti, zip) in parallelo, in due tempi ------
# Ogni lavoro k (0..N-1) ha tre funzioni:
#   TITOLO k           la riga "[zip k/N] ..." (o "[file ...]")
#   PREPARA k CARTELLA la parte lenta: unzip, impronte, exiftool. Scrive solo dentro la
#                      sua CARTELLA (in TMP): mai ordinate/ né i file di lavoro.
#   APPLICA k CARTELLA il resto, in questo processo e sempre nell'ordine di k (come prima,
#                      per avere gli stessi nomi _2, -1 e la stessa uscita): metti_in_ordinate,
#                      manifest.tsv, fonti.tsv, da-controllare.txt, la riga dell'esito.
# Con PARALLELI=1 si fa tutto in fila, come sempre. Con PARALLELI=N, N preparazioni girano
# in sottoshell in background (ognuna con la sua uscita in CARTELLA/.uscita e .errori, che
# si ristampano col titolo, così l'uscita è identica) mentre questo processo applica i
# lavori pronti, uno alla volta e in ordine. Una nuova preparazione parte solo quando la
# precedente in ordine è pronta da applicare: su disco ci sono al massimo N+1 lavori aperti.
# Sottoshell e non "xargs -P" sullo script: vedono già tutte le variabili e funzioni
# (impostazioni, elenco degli zip), e l'ordine di applicazione resta semplice.
# Uno zip è fatto solo quando è in fonti.tsv, scritto da APPLICA: se il giro si interrompe,
# quello che era solo preparato si rifà al prossimo raccogli.
# Ctrl+C o kill: la trappola ferma le preparazioni (ognuna ha un suo gruppo di processi,
# con dentro unzip, exiftool, sha256sum, e si ferma tutto insieme) e il giro si chiude tra un
# file e l'altro di ordinate/, mai a metà di uno.
PARALLELI="${PARALLELI:-4}"
FERMATO=""
declare -A PREPARAZIONI=()
fermato() {
    [ -z "$FERMATO" ] && return 0
    ferma_preparazioni
    echo "Interrotto: rilancia $0 raccogli per riprendere." >&2
    exit "$FERMATO"
}
ferma_preparazioni() {
    local k
    for k in "${!PREPARAZIONI[@]}"; do kill -TERM -- "-${PREPARAZIONI[$k]}" 2>/dev/null || true; done
    for k in "${!PREPARAZIONI[@]}"; do wait "${PREPARAZIONI[$k]}" 2>/dev/null || true; done
    PREPARAZIONI=()
}

in_fila() {
    local n="$1" titolo="$2" prepara="$3" applica="$4" k dir prossimo
    [ "$n" -gt 0 ] || return 0
    trap 'FERMATO=130; ferma_preparazioni' INT
    trap 'FERMATO=143; ferma_preparazioni' TERM
    trap 'ferma_preparazioni' EXIT
    if [ "$PARALLELI" -le 1 ]; then
        # In fila: la cartella del lavoro è TMP stessa, come sempre.
        for ((k = 0; k < n; k++)); do
            dir="$TMP"
            mkdir -p "$dir"
            "$titolo" "$k"
            "$prepara" "$k" "$dir"
            fermato
            "$applica" "$k" "$dir"
            rm -rf "$dir"
            fermato
        done
    else
        for ((prossimo = 0; prossimo < n && prossimo < PARALLELI; prossimo++)); do
            avvia_preparazione "$prossimo" "$prepara"
        done
        for ((k = 0; k < n; k++)); do
            dir="$TMP/$$-$k"
            wait "${PREPARAZIONI[$k]}" || true
            unset 'PREPARAZIONI[$k]'
            fermato
            "$titolo" "$k"
            ristampa "$dir" < "$dir/.uscita"
            ristampa "$dir" < "$dir/.errori" >&2
            [ -f "$dir/.pronto" ] || errore "la preparazione non è finita bene (vedi sopra): rilancia $0 raccogli"
            if [ "$prossimo" -lt "$n" ]; then
                avvia_preparazione "$prossimo" "$prepara"
                prossimo=$((prossimo + 1))
            fi
            "$applica" "$k" "$dir"
            rm -rf "$dir"
            fermato
        done
    fi
    trap - INT TERM EXIT
}

# L'uscita di una preparazione (unzip che si lamenta di uno zip rovinato, ...) con TMP al
# posto della sua cartella: la stessa che in fila.
ristampa() {
    local testo
    testo="$(cat; echo .)"
    testo="${testo%.}"
    printf '%s' "${testo//"$1/"/"$TMP/"}"
}

# Una preparazione in background, in un gruppo di processi suo (set -m solo per questo
# avvio): kill -- -PID ferma lei e tutto quello che ha lanciato. Dentro, set -e come qui:
# se qualcosa va storto non si scrive .pronto.
avvia_preparazione() {
    local k="$1" prepara="$2" dir="$TMP/$$-$1"
    mkdir -p "$dir"
    set -m
    ( set +m; trap - INT TERM EXIT
      "$prepara" "$k" "$dir" > "$dir/.uscita" 2> "$dir/.errori"
      : > "$dir/.pronto" ) < /dev/null &
    PREPARAZIONI[$k]=$!
    set +m
}

# Le impronte dei file di una cartella in ordine di nome, come le legge metti_in_ordinate.
impronte_di_cartella() {
    find "$1" -type f -print0 | sort -z | xargs -0 -r sha256sum -z
}

# Il percorso sotto RADICE di un file letto da BASE (che può essere la copia locale).
# Un nome accorciato da scarica (LAVORO/nomi-lunghi.tsv) torna quello vero di pCloud.
declare -A NOMI_LUNGHI=()
fonte_di() { fonte_in "$1"; printf '%s' "$FONTE"; }
# Come fonte_di, ma in FONTE e senza sottoshell: nei giri su 100 mila file conta.
fonte_in() {
    local rel="${1#"$BASE"/}"
    if [ "$BASE" = "$SPECCHIO" ] && [ -n "${NOMI_LUNGHI[$rel]:-}" ]; then rel="${NOMI_LUNGHI[$rel]}"; fi
    FONTE="$RADICE/$rel"
}
carica_nomi_lunghi() {
    local corto lungo
    [ -f "$LAVORO/nomi-lunghi.tsv" ] || return 0
    while IFS=$'\t' read -r corto lungo; do NOMI_LUNGHI[$corto]="$lungo"; done < "$LAVORO/nomi-lunghi.tsv"
}

# Un blocco di file sciolti: li collega (o copia, da P:), li ordina, segna da dove vengono.
# Dalla copia locale sono collegamenti: niente spazio in più, e ordinate/ li tiene.
# Il blocco k sono i file sciolti da k*blocco, al massimo blocco.
titolo_sciolti() {
    local i=$(($1 * blocco))
    echo "[file $((i + 1))-$((i + blocco < ${#sciolti[@]} ? i + blocco : ${#sciolti[@]}))/${#sciolti[@]}] $(dirname "${sciolti[$i]#"$BASE"/}")/"
}
prepara_sciolti() {
    local d="$2"
    local -a del_blocco=("${sciolti[@]:$(($1 * blocco)):blocco}")
    mkdir -p "$d/in" "$d/ord"
    local -A VISTO
    local -a copie=()
    local f nome copia sha k=0
    for f in "${del_blocco[@]}"; do
        k=$((k + 1))
        nome="${f##*/}"
        # Copie di un download: "IMG_0001 (1).jpg" torna "IMG_0001.jpg", e di più file
        # identici si ordina solo il primo (così resta il nome senza " (1)").
        if [ "$COPIE_SCARICATE" = si ] && [[ "$nome" =~ ^(.+)\ \([0-9]+\)(\.[^.]+)$ ]]; then
            nome="${BASH_REMATCH[1]}${BASH_REMATCH[2]}"
        fi
        copia="$d/in/$k/$nome"
        mkdir -p "$d/in/$k"
        ln "$f" "$copia" 2>/dev/null || cp --preserve=timestamps "$f" "$copia"
        copie+=("$copia")
    done
    impronte < <(printf '%s\0' "${copie[@]}")
    # L'impronta di ogni file del blocco, nell'ordine del blocco, per applica_sciolti.
    : > "$d/impronte"
    for copia in "${copie[@]}"; do
        sha="${SHA[$copia]}"
        printf '%s\n' "$sha" >> "$d/impronte"
        [ "$COPIE_SCARICATE" = si ] && [ -n "${VISTO[$sha]:-}" ] && rm -f "$copia"
        VISTO[$sha]=1
    done
    ordina_cartella "$d/in" "$d/ord"
    impronte_di_cartella "$d/ord" > "$d/ord.sha"
}
applica_sciolti() {
    local d="$2"
    local -a del_blocco=("${sciolti[@]:$(($1 * blocco)):blocco}") shas=()
    mapfile -t shas < "$d/impronte"
    metti_in_ordinate "$d/ord" "$d/ord.sha"
    local f fonte sha k=0
    for f in "${del_blocco[@]}"; do
        sha="${shas[$k]}"; k=$((k + 1))
        fonte_in "$f"; fonte="$FONTE"
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
# Lo zip k è zip[k].
titolo_zip() {
    echo "[zip $(($1 + 1))/${#zip[@]}] ${zip[$1]#"$BASE"/}"
}
prepara_zip() {
    local letto="${zip[$1]}" d="$2" stato=0
    mkdir -p "$d/zip" "$d/in" "$d/ord"
    if [ "$BASE" = "$SPECCHIO" ] || [ "$ZIP_SUL_POSTO" = si ]; then
        unzip -q -o "$letto" -d "$d/in" </dev/null || stato=$?
    else
        cp "$letto" "$d/zip/a.zip"
        unzip -q -o "$d/zip/a.zip" -d "$d/in" </dev/null || stato=$?
        rm -f "$d/zip/a.zip"
    fi
    echo "$stato" > "$d/stato"
    [ "$stato" -gt 1 ] && return 0
    # Le copie "._nome" dei Mac non sono foto.
    rm -rf "$d/in/__MACOSX"
    find "$d/in" -name '._*' -type f -delete
    local s
    for s in "${SCARTA_NEI_ZIP[@]}"; do
        find "$d/in" -iname "$s" -type f -delete
    done
    # I media dello zip con la loro impronta (nome NUL impronta NUL), per applica_zip.
    local m
    local -a percorsi=()
    while IFS= read -r -d '' m; do percorsi+=("$m"); done \
        < <(find "$d/in" -type f \( -false "${estensioni_find[@]}" \) -print0 | sort -z)
    [ "${#percorsi[@]}" -gt 0 ] && impronte < <(printf '%s\0' "${percorsi[@]}")
    for m in "${percorsi[@]}"; do
        printf '%s\0%s\0' "${m#"$d/in"/}" "${SHA[$m]}"
    done > "$d/membri"
    ordina_cartella "$d/in" "$d/ord"
    impronte_di_cartella "$d/ord" > "$d/ord.sha"
}
applica_zip() {
    local d="$2" z stato
    z="$(fonte_di "${zip[$1]}")"
    stato="$(cat "$d/stato")"
    if [ "$stato" -gt 1 ]; then
        echo "zip rovinato (unzip $stato): $z" >> "$DA_CONTROLLARE"
        printf 'zip\t%s\tno\n' "$z" >> "$FONTI"
        return
    fi
    local -a membri=() shas=()
    local m s
    while IFS= read -r -d '' m && IFS= read -r -d '' s; do
        membri+=("$m"); shas+=("$s")
    done < "$d/membri"
    metti_in_ordinate "$d/ord" "$d/ord.sha"
    local altri eliminabile=si i
    altri=$(find "$d/in" -type f | wc -l)
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
        echo "zip con $altri altri file, resta $DOVE_ORIGINE: $z" >> "$DA_CONTROLLARE"
    fi
    printf 'zip\t%s\t%s\n' "$z" "$eliminabile" >> "$FONTI"
    echo "    ${#membri[@]} foto e video$([ "$altri" -gt 0 ] && echo ", $altri altri file: lo zip resta $DOVE_ORIGINE")"
}

# ordinate/ -> DESTINAZIONE, poi controlla; se torna scrive fase-carica.ok.
carica_ordinate() {
    richiede "$RCLONE"
    [ -d "$ORDINATE" ] || errore "prima: $0 raccogli"
    echo "Copio $ORDINATE in $DESTINAZIONE…"
    rc copy "$ORDINATE" "$DESTINAZIONE" --backup-dir "${DESTINAZIONE}-sovrascritte" \
        --transfers 4 --stats-one-line --stats 30s --stats-log-level NOTICE
    echo "Controllo che ci sia tutto…"
    if rc check "$ORDINATE" "$DESTINAZIONE" --one-way --size-only; then
        date +%s > "$LAVORO/fase-carica.ok"
        echo "Caricate e controllate. Ora nell'app: Monta, poi Indicizza archivio."
    else
        errore "su $DESTINAZIONE manca qualcosa o è diverso: rilancia $0 carica"
    fi
}

# ordinate/ -> ARCHIVIO/AAAA/AAAA-MM.zip, un mese alla volta, controllato; poi fase-archivia.ok.
archivia_mesi() {
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
            errore "lo zip di $mese $DOVE_ARCHIVIO non torna: rilancia $0 archivia"
        fi
        printf '%s\t%s\n' "$mese" "$dest" >> "$ARCHIVIATI"
        rm -f "$TMP/mese.zip"
    done < <(cd "$ORDINATE" && find . -mindepth 2 -maxdepth 2 -type d | sed 's|^\./||' | sort)
    date +%s > "$LAVORO/fase-archivia.ok"
    echo "Archiviati $DOVE_ARCHIVIO in $ARCHIVIO."
}
