#!/usr/bin/env bash
# Ricomprime sul PC i video del telefono troppo grandi per LifetimeCloud (oltre 20480 MB, o
# rifiutati con 413), che il server mette in coda senza toccarli (FOTOTIMELINE_VIDEO_GRANDI_DOVE=pc,
# DEPLOY.md "Video troppo grandi"). server2 non ha lo spazio per un video da 20 GB più il
# ricompresso, e la CPU gli serve per altro; il PC sì. Gira sul PC (WSL), con rclone (remote
# "pcloud:" e "lifetime:"), ffmpeg e ssh verso server2.
#
#   ricomprimi-video-grandi.sh elenco      la coda su server2, in sola lettura: stato, dimensione,
#                                          percorso su pCloud di ogni video, e lo spazio che serve qui
#   ricomprimi-video-grandi.sh anteprima   uguale a elenco
#   ricomprimi-video-grandi.sh ricomprimi  in primo piano: i video "da ricomprimere sul PC", uno alla volta
#   ricomprimi-video-grandi.sh avvia       come ricomprimi, in sottofondo (si può chiudere il terminale)
#   ricomprimi-video-grandi.sh stato       a che punto è: video, fase, percentuale, quanto manca
#   ricomprimi-video-grandi.sh ferma       ferma quello in sottofondo; un rilancio riprende da lì
#
# Per ogni video, come la ricompressione del server (VideoGrandi):
#   1. lo spazio in LAVORO (~/video-grandi): originale + ricompresso + RISERVA_GB; se manca si ferma
#   2. rclone scarica l'originale da pCloud in LAVORO/<id>/ (solo lettura su pCloud)
#   3. ffprobe ne legge la durata; nice -n 19 ffmpeg -map 0 -map_metadata 0 -c copy -c:v libx265
#      -crf 24 -preset fast -tag:v hvc1 -movflags +use_metadata_tags -progress (tutti i flussi e i metadati,
#      quindi la data di ripresa; x265 coi parametri normali, niente limiti di memoria né -fs).
#      Il risultato dev'essere sotto MASSIMO_MB e sotto il 95% dell'originale, altrimenti CRF 28 e 32;
#      la durata quella dell'originale entro 1 s, altrimenti è un errore
#   4. rclone lo carica in LIFETIME/DAL_PC/<id>.mp4 (lifetime:video-grandi-dal-pc), fuori dalla
#      cartella automatica, e controlla che sia arrivato intero; se LifetimeCloud lo rifiuta per la
#      dimensione (403 "File too large", 413), CRF più alto
#   5. su server2 segna il video RICOMPRESSO_PC con dimensione, CRF e SHA-256 (un updateOne che vale
#      solo se il video è ancora DA_RICOMPRIMERE_PC). Il resto lo fa il server: entro 5 minuti lo
#      porta nella cartella automatica col nome dell'originale, l'importazione ne fa la scheda e solo
#      allora, al giro dopo, sposta l'originale in pcloud:Video originali troppo grandi/.
# Lo script non cancella né sposta niente su pCloud e non scrive altro nel DB: solo i campi di quel
# video (sulPc, l'avanzamento per la pagina; stato, hash, crf, dimensioneRicompresso alla fine; stato
# ERRORE e messaggio per un errore vero, che nell'app si rifà con "Riprova"). Toglie solo i suoi file:
# le copie in LAVORO/<id> dopo il caricamento (TIENI=si per tenerle) e un suo caricamento rifiutato.
#
# Ripresa: quello già fatto resta in LAVORO/<id> (originale scaricato, ricompresso controllato in
# ricompresso.ok, caricato.ok): un rilancio non riscarica né ricomprime. Quale video fare lo dice il
# server: solo quelli DA_RICOMPRIMERE_PC, quindi un video già consegnato non si rifà. Il registro
# di quello che è successo è LAVORO/registro.tsv; in sottofondo l'uscita va in LAVORO/ricomprimi.log.
#
# Quanto dura: x265 "fast" su un 4K va a pochi fotogrammi al secondo; un video da 20 GB (20-30 minuti
# di 4K) vuole ore. Lo stato dice la velocità (1x = tempo reale) e quanto manca.
#
# Altre variabili (si danno sulla riga di comando, per esempio MASSIMO_MB=19000 ... avvia):
#   LAVORO=~/video-grandi  MASSIMO_MB=20000 (= FOTOTIMELINE_VIDEO_MAX_MB)  RISERVA_GB=2
#   CRF=24 CRF_MASSIMO=32 PASSO_CRF=4 PRESET=fast  X265_PARAMETRI= (altri -x265-params, vuoto = normali)
#   LIFETIME=lifetime:  DAL_PC=video-grandi-dal-pc (= FOTOTIMELINE_VIDEO_GRANDI_DAL_PC)
#   PCLOUD=             un altro remote (o una cartella) al posto di "pcloud:" della sorgente
#   FT_SSH=server2  FT_MONGO=fototimeline-mongo-1  FT_DB_NOME=fototimeline
#   FT_MONGOSH=comando  al posto di ssh+mongosh: riceve lo script come argomento (per i test)
#   TIENI=si            non togliere le copie in LAVORO/<id> dopo il caricamento
#
# Serve: rclone, ffmpeg (ffprobe), ssh, flock (sudo apt install -y ffmpeg rclone util-linux openssh-client).
set -euo pipefail

SE="$(readlink -f "${BASH_SOURCE[0]}")"
FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/video-grandi}"
RCLONE="${RCLONE:-rclone}"
FFMPEG="${FFMPEG:-ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"
MASSIMO_MB="${MASSIMO_MB:-20000}"
RISERVA_GB="${RISERVA_GB:-2}"
CRF="${CRF:-24}"
CRF_MASSIMO="${CRF_MASSIMO:-32}"
PASSO_CRF="${PASSO_CRF:-4}"
PRESET="${PRESET:-fast}"
X265_PARAMETRI="${X265_PARAMETRI:-}"
TOLLERANZA_DURATA="${TOLLERANZA_DURATA:-1}"
LIFETIME="${LIFETIME:-lifetime:}"
DAL_PC="${DAL_PC:-video-grandi-dal-pc}"
PCLOUD="${PCLOUD:-}"
FT_SSH="${FT_SSH:-server2}"
FT_MONGO="${FT_MONGO:-fototimeline-mongo-1}"
FT_DB_NOME="${FT_DB_NOME:-fototimeline}"
FT_MONGOSH="${FT_MONGOSH:-}"
TIENI="${TIENI:-}"
# Ogni quanti secondi si guarda ffmpeg, e ogni quanti si scrive l'avanzamento nel DB (per la pagina).
OGNI="${OGNI:-5}"
AVVISA_OGNI="${AVVISA_OGNI:-600}"
# Solo per i test: lo spazio libero in LAVORO, in byte, invece di chiederlo a df.
SPAZIO_LIBERO="${SPAZIO_LIBERO:-}"
COLLEZIONE=video_grandi_telefono
MB=$((1024 * 1024))

REGISTRO="$LAVORO/registro.tsv"   # data \t id \t fatto|errore|rimandato \t nome \t dettagli
CORRENTE="$LAVORO/corrente"       # una riga: il video in lavorazione e a che punto è
LOG="$LAVORO/ricomprimi.log"
PID="$LAVORO/pid"
CODA="$LAVORO/coda.tsv"           # l'ultima coda letta da server2
mkdir -p "$LAVORO"

errore() { echo "Errore: $*" >&2; exit 1; }
richiede() {
    for c in "$@"; do
        command -v "$c" >/dev/null || errore "manca $c (sudo apt install -y ffmpeg rclone util-linux openssh-client)"
    done
}
rc() { "$RCLONE" "$@"; }
ora() { date '+%d/%m %H:%M'; }
# "850 MB", "23,1 GB", in base 1024 come le conta LifetimeCloud (e il server).
peso() { awk -v b="$1" 'BEGIN { mb = b / 1048576; if (mb >= 1024) printf "%.1f GB", mb / 1024; else printf "%d MB", mb + 0.5 }' | tr . ,; }
durata_testo() { awk -v s="$1" 'BEGIN { s = int(s + 0.5); if (s >= 3600) printf "%d h %02d min", s / 3600, (s % 3600) / 60; else printf "%d min %02d s", s / 60, s % 60 }'; }
# Un percorso dentro un remote ("lifetime:" + "a/b" = "lifetime:a/b") o una cartella.
dentro() {
    local base="$1" rel="${2#/}"
    [ -z "$rel" ] && { printf '%s' "$base"; return; }
    case "$base" in *: | */) printf '%s%s' "$base" "$rel" ;; *) printf '%s/%s' "$base" "$rel" ;; esac
}
in_corso() { [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; }
registra() { printf '%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M')" "$1" "$2" "$3" "${4//$'\t'/ }" >> "$REGISTRO"; }
corrente() { printf '%s\n' "$*" > "$CORRENTE"; echo "  $*"; }

# --- il DB su server2 ------------------------------------------------------------------------
# Lo script per mongosh passa in base64, come in confronta-tutto.sh: niente virgolette da
# proteggere fra ssh, docker e sh. Con FT_MONGOSH lo riceve quel comando (i test).
mongo_js() {
    if [ -n "$FT_MONGOSH" ]; then
        "$FT_MONGOSH" "$1"
        return
    fi
    local js
    js="$(printf '%s' "$1" | base64 -w0)"
    # shellcheck disable=SC2029
    ssh -o BatchMode=yes "$FT_SSH" "docker exec $FT_MONGO sh -c 'mongosh --quiet --norc -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin $FT_DB_NOME --eval \"\$(echo $js | base64 -d)\"'" < /dev/null
}

# Una stringa per JavaScript, fra virgolette (JSON: le virgolette e le barre si proteggono).
js_testo() {
    local s="${1//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/ }"
    s="${s//$'\t'/ }"
    printf '"%s"' "$s"
}
# shellcheck disable=SC2016
js_id() { printf '{$in: [ObjectId(%s), %s]}' "$(js_testo "$1")" "$(js_testo "$1")"; }

# La coda, in sola lettura (find): una riga per video, poi #fine e quanti.
leggi_coda() {
    local js out
    js="$(cat <<FINE
var c = db.getSiblingDB("$FT_DB_NOME").getCollection("$COLLEZIONE"), n = 0;
function campo(x) { return x === undefined || x === null ? "" : String(x).replace(/[\t\r\n]/g, " "); }
function id(x) { return x && x.toHexString ? x.toHexString() : String(x); }
c.find({}).sort({creatoIl: 1}).forEach(function (v) {
  n++;
  print([id(v._id), v.stato, v.dimensione, v.sorgente, v.percorso, v.dimensioneRicompresso, v.crf,
         v.sulPc || v.messaggio].map(campo).join("\t"));
});
print("#fine\t" + n);
FINE
)"
    out="$(mongo_js "$js")" || errore "non riesco a leggere la coda su $FT_SSH: ssh $FT_SSH funziona? (DEPLOY.md, \"Video troppo grandi\")"
    printf '%s\n' "$out" | grep -q '^#fine' || errore "la coda letta da $FT_SSH è a metà (manca #fine): rilancia"
    printf '%s\n' "$out" | awk -F'\t' '!/^#fine/ && NF == 8' > "$CODA"
}

# Aggiorna i campi di un video, solo se è ancora DA_RICOMPRIMERE_PC. Stampa quanti ne ha trovati (0 o 1).
aggiorna_video() {   # aggiorna_video ID '{campo: valore, ...}'
    local out
    out="$(mongo_js "var r = db.getSiblingDB(\"$FT_DB_NOME\").getCollection(\"$COLLEZIONE\").updateOne(
  {_id: $(js_id "$1"), stato: \"DA_RICOMPRIMERE_PC\"}, {\$set: $2});
print(\"#trovati\t\" + r.matchedCount);")" || return 1
    printf '%s\n' "$out" | sed -n 's/^#trovati\t//p' | tail -1
}
# L'avanzamento per la pagina Telefoni: se non si scrive, pazienza.
sul_pc() { aggiorna_video "$1" "{sulPc: $(js_testo "sul PC: $2")}" > /dev/null 2>&1 || true; }

# --- elenco ----------------------------------------------------------------------------------
elenco() {
    [ -n "$FT_MONGOSH" ] || richiede ssh base64
    echo "La coda dei video troppo grandi su $FT_SSH (sola lettura)…"
    leggi_coda
    local id stato dim sorgente percorso dopo crf nota da_fare=0 serve=0 piu
    if [ ! -s "$CODA" ]; then echo "Nessun video troppo grande."; return 0; fi
    printf '%-20s %10s %10s  %s\n' stato prima dopo percorso
    while IFS=$'\t' read -r id stato dim sorgente percorso dopo crf nota; do
        printf '%-20s %10s %10s  %s\n' "$stato" "$(peso "$dim")" "$([ -n "$dopo" ] && peso "$dopo" || echo -)" "$(dentro "$sorgente" "$percorso")"
        [ -n "$nota" ] && printf '%-20s %10s %10s  %s\n' "" "" "" "$nota"
        if [ "$stato" = DA_RICOMPRIMERE_PC ]; then
            da_fare=$((da_fare + 1))
            piu=$((dim + (dim < MASSIMO_MB * MB ? dim : MASSIMO_MB * MB) + RISERVA_GB * 1024 * MB))
            [ "$piu" -gt "$serve" ] && serve="$piu"
        fi
    done < "$CODA"
    echo
    if [ "$da_fare" -eq 0 ]; then
        echo "Niente da ricomprimere sul PC."
        return 0
    fi
    echo "$da_fare da ricomprimere sul PC, uno alla volta: in $LAVORO servono al massimo $(peso "$serve"), liberi $(peso "$(libero)")."
    echo "Per partire:  $0 avvia      (poi $0 stato per seguirlo)"
}

libero() {
    if [ -n "$SPAZIO_LIBERO" ]; then printf '%s' "$SPAZIO_LIBERO"; return; fi
    df -B1 --output=avail "$LAVORO" | tail -1 | tr -d ' '
}

# --- ricomprimi ------------------------------------------------------------------------------
# La durata in secondi (ffprobe), vuota se non la dice.
durata() { "$FFPROBE" -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$1" 2>/dev/null | head -1 | grep -E '^[0-9.]+$' || true; }
risoluzione() { "$FFPROBE" -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "$1" 2>/dev/null | head -1 || true; }
dimensione_su() {   # dimensione_su REMOTE/FILE: i byte, vuoto se non c'è
    rc size --json "$1" 2>/dev/null | sed -n 's/.*"count": *1,.*"bytes": *\([0-9]*\).*/\1/p'
}

# ffmpeg con -progress: ogni OGNI secondi la percentuale, la velocità e quanto manca in CORRENTE,
# ogni AVVISA_OGNI nel DB. Esce col codice di ffmpeg.
ffmpeg_seguito() {   # ffmpeg_seguito ID NOME CRF DURATA ENTRATA USCITA
    local id="$1" nome="$2" crf="$3" tot="$4" in="$5" out="$6" dir prog pid t us vel fatto pc resto ultimo=0 stato=0
    dir="$(dirname "$out")"; prog="$dir/progresso"
    : > "$prog"
    # log-level=error come sul server: x265 non riempie il registro.
    nice -n 19 "$FFMPEG" -y -nostdin -v error -nostats -progress "$prog" -i "$in" \
        -map 0 -map_metadata 0 -c copy -c:v libx265 -crf "$crf" -preset "$PRESET" \
        -x265-params "log-level=error${X265_PARAMETRI:+:$X265_PARAMETRI}" \
        -tag:v hvc1 -movflags +use_metadata_tags -f mp4 "$out" < /dev/null &
    pid=$!
    # Fermato (ferma, Ctrl+C): si ferma anche ffmpeg, e il rilancio rifà da capo solo questo CRF.
    trap 'kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; echo "Fermato: rilancia $0 ricomprimi per riprendere." >&2; exit 143' TERM INT
    while kill -0 "$pid" 2>/dev/null; do
        sleep "$OGNI"
        us="$(grep '^out_time_us=' "$prog" 2>/dev/null | tail -1 | cut -d= -f2)" || true
        vel="$(grep '^speed=' "$prog" 2>/dev/null | tail -1 | cut -d= -f2 | tr -d 'x ')" || true
        [[ "$us" =~ ^[0-9]+$ ]] || continue
        read -r fatto pc resto < <(awk -v us="$us" -v tot="$tot" -v v="$vel" 'BEGIN {
            f = us / 1e6; p = tot > 0 ? int(f * 100 / tot) : 0; if (p > 100) p = 100
            r = (v + 0 > 0) ? (tot - f) / v : -1; printf "%.0f %d %.0f\n", f, p, r }')
        t="ricomprimo in H.265 (CRF $crf) $pc%, $(durata_testo "$fatto") di $(durata_testo "$tot")"
        if [ -n "$vel" ] && [ "$resto" -ge 0 ]; then t="$t, velocità ${vel}x, mancano circa $(durata_testo "$resto")"; fi
        printf '%s: %s\n' "$nome" "$t" > "$CORRENTE"
        if [ $(( $(date +%s) - ultimo )) -ge "$AVVISA_OGNI" ]; then
            ultimo=$(date +%s)
            echo "  [$(ora)] $t"
            sul_pc "$id" "$t"
        fi
    done
    wait "$pid" || stato=$?
    trap - TERM INT
    return "$stato"
}

# Un video: 0 consegnato, 1 da riprovare più tardi (spazio, rete, LifetimeCloud), 2 errore vero.
uno() {   # uno ID DIMENSIONE SORGENTE PERCORSO
    local id="$1" dim="$2" sorgente="$3" percorso="$4"
    local nome="${percorso##*/}" ext dir orig ok fs rel origine dest
    ext="${nome##*.}"; ext="${ext,,}"
    dir="$LAVORO/$id"; orig="$dir/originale.$ext"; ok="$dir/ricompresso.ok"
    fs="${sorgente%%:*}:"; rel="${sorgente#*:}"; rel="${rel#/}"; rel="${rel%/}"
    [ -n "$PCLOUD" ] && [ "$fs" = "pcloud:" ] && fs="$PCLOUD"
    origine="$(dentro "$(dentro "$fs" "$rel")" "$percorso")"
    dest="$(dentro "$LIFETIME" "$DAL_PC/$id.mp4")"
    echo
    echo "===== $nome ($(peso "$dim")), $(ora) ====="
    mkdir -p "$dir"

    # 1-2. Lo spazio e l'originale (se non c'è già, intero, da un giro di prima).
    if [ ! -f "$ok" ] && { [ ! -f "$orig" ] || [ "$(stat -c %s "$orig")" != "$dim" ]; }; then
        local serve lib
        serve=$((dim + (dim < MASSIMO_MB * MB ? dim : MASSIMO_MB * MB) + RISERVA_GB * 1024 * MB))
        lib="$(libero)"
        if [ "$lib" -lt "$serve" ]; then
            local m="spazio insufficiente in $LAVORO: servono $(peso "$serve") (originale, ricompresso e $RISERVA_GB GB di margine), liberi $(peso "$lib")"
            corrente "$nome: $m"
            sul_pc "$id" "$m"
            registra "$id" rimandato "$nome" "$m"
            rmdir "$dir" 2>/dev/null || true
            return 1
        fi
        corrente "$nome: scarico l'originale da $origine"
        sul_pc "$id" "scarico l'originale da pCloud"
        if ! rc copyto "$origine" "$orig" --stats 1m --stats-one-line --stats-log-level NOTICE \
            || [ "$(stat -c %s "$orig" 2>/dev/null || echo 0)" != "$dim" ]; then
            local m="originale non scaricato intero da $origine ($dim byte attesi, $(stat -c %s "$orig" 2>/dev/null || echo 0) arrivati)"
            corrente "$nome: $m"
            registra "$id" rimandato "$nome" "$m"
            return 1
        fi
    elif [ -f "$ok" ]; then
        echo "  già ricompresso in un giro di prima ($dir): non riscarico né ricomprimo"
    else
        echo "  originale già scaricato in un giro di prima"
    fi

    # 3. La ricompressione, col CRF via via più alto finché sta sotto il limite.
    local tot limite crf=$CRF d dopo sha caricato="" ultimo="" uscita
    limite=$((MASSIMO_MB * MB))
    [ $((dim * 95 / 100)) -lt "$limite" ] && limite=$((dim * 95 / 100))
    if [ -f "$ok" ]; then
        read -r crf d sha < "$ok"
        tot=""
    else
        tot="$(durata "$orig")"
        [ -n "$tot" ] || { errore_vero "$id" "$nome" "ffprobe non dice la durata dell'originale: il risultato non si potrebbe controllare"; return 2; }
        echo "  durata del video: $(durata_testo "$tot"), $(risoluzione "$orig"); x265 \"$PRESET\" su un 4K fa pochi fotogrammi al secondo, anche ore"
    fi
    while true; do
        if [ ! -f "$ok" ]; then
            [ "$crf" -le "$CRF_MASSIMO" ] || { errore_vero "$id" "$nome" "ancora troppo grande anche con CRF $CRF_MASSIMO: $ultimo"; return 2; }
            corrente "$nome: ricomprimo in H.265 (CRF $crf), $(durata_testo "$tot") di video"
            sul_pc "$id" "ricomprimo in H.265 (CRF $crf)"
            rm -f "$dir/ricompresso.parziale.mp4" "$dir/ricompresso.mp4"
            if ! ffmpeg_seguito "$id" "$nome" "$crf" "$tot" "$orig" "$dir/ricompresso.parziale.mp4"; then
                rm -f "$dir/ricompresso.parziale.mp4"
                errore_vero "$id" "$nome" "ffmpeg non è riuscito (CRF $crf): rilancia per riprovare, l'originale scaricato resta in $dir"
                return 2
            fi
            d="$(stat -c %s "$dir/ricompresso.parziale.mp4")"
            if [ "$d" -ge "$limite" ]; then
                ultimo="con CRF $crf ($(peso "$d")) supera $(peso "$limite")"
                echo "  $ultimo: riprovo più compresso"
                rm -f "$dir/ricompresso.parziale.mp4"
                crf=$((crf + PASSO_CRF))
                continue
            fi
            dopo="$(durata "$dir/ricompresso.parziale.mp4")"
            if [ -z "$dopo" ] || awk -v a="$dopo" -v b="$tot" -v t="$TOLLERANZA_DURATA" 'BEGIN { d = a - b; if (d < 0) d = -d; exit !(d > t) }'; then
                rm -f "$dir/ricompresso.parziale.mp4"
                errore_vero "$id" "$nome" "la durata del ricompresso (${dopo:-sconosciuta} s) non è quella dell'originale ($tot s, tolleranza $TOLLERANZA_DURATA s), CRF $crf"
                return 2
            fi
            mv "$dir/ricompresso.parziale.mp4" "$dir/ricompresso.mp4"
            sha="$(sha256sum "$dir/ricompresso.mp4" | cut -c1-64)"
            printf '%s %s %s\n' "$crf" "$d" "$sha" > "$ok"
            echo "  ricompresso: $(peso "$dim") → $(peso "$d") (CRF $crf), durata uguale"
        fi

        # 4. Il caricamento nella cartella dal PC, controllato.
        if [ ! -f "$dir/ricompresso.mp4" ] || [ "$(stat -c %s "$dir/ricompresso.mp4")" != "$d" ]; then
            rm -f "$ok" "$dir/caricato.ok"
            corrente "$nome: il ricompresso di prima non c'è più: rilancia, lo rifà"
            registra "$id" rimandato "$nome" "ricompresso di prima sparito da $dir"
            return 1
        fi
        if [ -f "$dir/caricato.ok" ]; then
            echo "  già caricato in $dest"
            break
        fi
        corrente "$nome: carico il ricompresso in $dest"
        sul_pc "$id" "carico il ricompresso su LifetimeCloud ($(peso "$d"))"
        uscita="$(rc copyto "$dir/ricompresso.mp4" "$dest" --stats 1m --stats-one-line --stats-log-level NOTICE 2>&1)" \
            && caricato="$(dimensione_su "$dest")" || caricato=""
        printf '%s\n' "$uscita" | sed '/^$/d; s/^/  /'
        if [ "$caricato" = "$d" ]; then
            : > "$dir/caricato.ok"
            # La copia dell'originale sul PC non serve più (su pCloud resta com'era): spazio per il prossimo.
            [ "$TIENI" = si ] || rm -f "$orig"
            break
        fi
        if printf '%s' "$uscita" | grep -Eiq 'file too large|entity too large|payload too large|\b413\b'; then
            # LifetimeCloud non lo prende così: più compresso, e sotto quello che ha rifiutato.
            ultimo="con CRF $crf ($(peso "$d")) LifetimeCloud lo rifiuta"
            echo "  $ultimo: riprovo più compresso"
            rc deletefile "$dest" 2>/dev/null || true
            limite=$((d * 95 / 100))
            rm -f "$ok" "$dir/ricompresso.mp4"
            crf=$((crf + PASSO_CRF))
            if [ ! -f "$orig" ] || [ "$(stat -c %s "$orig")" != "$dim" ]; then
                # Solo se un giro di prima l'ha tolto (TIENI no e caricato altrove): si riscarica.
                corrente "$nome: va ricompresso di nuovo, rilancia: riscarica l'originale"
                registra "$id" rimandato "$nome" "$ultimo"
                return 1
            fi
            [ -n "$tot" ] || tot="$(durata "$orig")"
            continue
        fi
        local m="caricamento in $dest non riuscito (${caricato:-nessun file} invece di $d byte): rilancia, non si ricomprime di nuovo"
        corrente "$nome: $m"
        registra "$id" rimandato "$nome" "$m"
        return 1
    done

    # 5. Il segnale al server: solo se il video è ancora da ricomprimere sul PC.
    local trovati
    trovati="$(aggiorna_video "$id" "{stato: \"RICOMPRESSO_PC\", hash: $(js_testo "$sha"), crf: NumberInt($crf),
    dimensioneRicompresso: NumberLong($(js_testo "$d")), sulPc: $(js_testo "sul PC: ricompresso, $(peso "$dim") → $(peso "$d") (CRF $crf)"),
    messaggio: null, aggiornatoIl: new Date()}")" || { corrente "$nome: caricato, ma non riesco a dirlo a $FT_SSH: rilancia"; registra "$id" rimandato "$nome" "segnale al server non riuscito"; return 1; }
    if [ "$trovati" != 1 ]; then
        corrente "$nome: su $FT_SSH non è più da ricomprimere sul PC (annullato?): il ricompresso resta in $dest, niente segnato"
        registra "$id" rimandato "$nome" "non più DA_RICOMPRIMERE_PC sul server; ricompresso in $dest"
        return 1
    fi
    registra "$id" fatto "$nome" "$(peso "$dim") → $(peso "$d"), CRF $crf, in $dest, sha256 $sha"
    corrente "$nome: fatto, $(peso "$dim") → $(peso "$d") (CRF $crf); il server lo prende entro pochi minuti"
    [ "$TIENI" = si ] || rm -rf "$dir"
    return 0
}

# Un errore che non passa da solo (durata diversa, ancora troppo grande, ffmpeg): il video va in
# ERRORE sul server col motivo, e si rifà con "Riprova" nella pagina Telefoni.
errore_vero() {   # errore_vero ID NOME MOTIVO
    corrente "$2: NON RIUSCITO: $3"
    registra "$1" errore "$2" "$3"
    aggiorna_video "$1" "{stato: \"ERRORE\", messaggio: $(js_testo "sul PC: $3"), sulPc: null, aggiornatoIl: new Date()}" > /dev/null \
        || echo "  (e non riesco a scriverlo su $FT_SSH)" >&2
}

ricomprimi() {
    richiede "$RCLONE" "$FFMPEG" "$FFPROBE" sha256sum nice
    [ -n "$FT_MONGOSH" ] || richiede ssh base64
    "$FFMPEG" -hide_banner -encoders 2>/dev/null | grep libx265 >/dev/null || errore "$FFMPEG non ha libx265 (sudo apt install -y ffmpeg)"
    if command -v flock >/dev/null; then
        exec 9>"$LAVORO/in-corso.lock"
        flock -n 9 || errore "c'è già un giro in corso: $0 stato"
    fi
    echo "$$" > "$PID"
    trap 'rm -f "$PID"' EXIT
    echo "Leggo la coda da $FT_SSH ($(ora))…"
    leggi_coda
    local -a fare=()
    local id stato dim sorgente percorso dopo crf nota fatti=0 rimandati=0 errori=0 esito
    while IFS=$'\t' read -r id stato dim sorgente percorso dopo crf nota; do
        [ "$stato" = DA_RICOMPRIMERE_PC ] && fare+=("$id"$'\t'"$dim"$'\t'"$sorgente"$'\t'"$percorso")
    done < "$CODA"
    if [ "${#fare[@]}" -eq 0 ]; then
        echo "Da fare: niente (nessun video da ricomprimere sul PC su $FT_SSH)."
        echo "Niente da fare $(ora)." > "$CORRENTE"
        return 0
    fi
    echo "Da fare: ${#fare[@]} video, uno alla volta."
    local riga
    for riga in "${fare[@]}"; do
        IFS=$'\t' read -r id dim sorgente percorso <<< "$riga"
        esito=0
        uno "$id" "$dim" "$sorgente" "$percorso" || esito=$?
        case "$esito" in 0) fatti=$((fatti + 1)) ;; 1) rimandati=$((rimandati + 1)) ;; *) errori=$((errori + 1)) ;; esac
    done
    echo
    local fine="Finito $(ora): $fatti consegnati al server, $rimandati da riprovare, $errori non riusciti."
    echo "$fine"
    echo "$fine" > "$CORRENTE"
    [ "$fatti" -gt 0 ] && echo "Il server li porta nella cartella automatica entro 5 minuti; l'originale si sposta in \"Video originali troppo grandi\" dopo l'importazione."
    [ "$rimandati" -gt 0 ] && echo "Per riprovare quelli rimasti:  $0 ricomprimi   (riprende da dove era)"
    [ "$errori" -gt 0 ] && echo "I non riusciti sono in errore nella pagina Telefoni: \"Riprova\" li rimette in coda per il PC."
    [ "$rimandati" -eq 0 ] && [ "$errori" -eq 0 ]
}

avvia() {
    in_corso && errore "c'è già un giro in corso: $0 stato"
    : > "$LOG"
    setsid nohup "$SE" ricomprimi >> "$LOG" 2>&1 < /dev/null &
    echo "$!" > "$PID"
    echo "Partito in sottofondo."
    echo "Per vedere a che punto è:  $0 stato"
    echo "Per seguirlo:              tail -f $LOG"
    echo "Per fermarlo:              $0 ferma   (poi $0 avvia riprende)"
}

ferma() {
    if ! in_corso; then echo "Non c'è nessun giro in corso."; return 0; fi
    local p g i
    p="$(cat "$PID")"
    g="$(ps -o pgid= -p "$p" | tr -d ' ')"
    # Il giro in sottofondo ha il suo gruppo (setsid): si fermano anche ffmpeg e rclone.
    if [ "$g" = "$p" ]; then kill -TERM -- "-$p" 2>/dev/null || true; else kill -TERM "$p" 2>/dev/null || true; fi
    for ((i = 0; i < 300; i++)); do in_corso || break; sleep 0.1; done
    echo "Fermato. Quello già fatto resta in $LAVORO: $0 avvia riprende."
}

stato() {
    if in_corso; then echo "Sta girando (pid $(cat "$PID"))."; else echo "Non sta girando."; fi
    [ -f "$CORRENTE" ] && { echo "Adesso: $(cat "$CORRENTE")"; }
    if [ -s "$REGISTRO" ]; then
        echo "--- registro ($REGISTRO), ultime righe"
        tail -n 10 "$REGISTRO" | awk -F'\t' '{ printf "%s  %-9s %s: %s\n", $1, $3, $4, $5 }'
    fi
    if [ -s "$LOG" ]; then
        echo "--- ultime righe di $LOG"
        tail -n 8 "$LOG"
    fi
    echo "La coda su server2: $0 elenco"
}

case "$FASE" in
    elenco|anteprima) elenco ;;
    ricomprimi) ricomprimi ;;
    avvia) avvia ;;
    stato) stato ;;
    ferma) ferma ;;
    *) errore "uso: $0 [elenco | anteprima | ricomprimi | avvia | stato | ferma]" ;;
esac
