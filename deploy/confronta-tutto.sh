#!/usr/bin/env bash
# Confronta per contenuto (SHA-256) ogni foto e video che c'è *adesso* su pCloud, sciolti e
# dentro gli zip, con quello che FotoTimeline ha davvero: gli hash delle schede in MongoDB su
# server2 (Foto.hash è lo SHA-256 del file). Poi i file di LifetimeCloud contro le schede: chi
# non ha scheda, chi ha la scheda ma non il file, e un campione riscaricato e ricontrollato.
# Gira sul PC (WSL), dopo foto-da-pcloud.sh, con le sue impostazioni (LAVORO/impostazioni).
#
#   confronta-tutto.sh           elenca pCloud e dice quanto scaricherà (qualche minuto, in
#                                primo piano), poi parte in sottofondo dal passo 2
#   confronta-tutto.sh stima     solo il passo 1, in primo piano: l'elenco e la stima
#   confronta-tutto.sh dove      a che passo è, zip fatti, GB scaricati, quanto manca
#   confronta-tutto.sh da N      riparte dal passo N, in sottofondo (da 1: rifà anche l'elenco)
#   confronta-tutto.sh ferma     ferma il giro in sottofondo: quello già fatto resta
#   confronta-tutto.sh rapporto  rifà solo il confronto (passo 5), in primo piano
#
# I passi:
#   1 elenca        tutti i file di RADICE con rclone (remote PCLOUD): foto, video e zip, con
#                   dimensione e data. Salta ARCHIVIO, "Crypto Folder" e le cartelle di
#                   sistema come foto-da-pcloud.sh; RIMASTI ("Da controllare") invece si
#                   confronta: anche lì ci sono foto. ESCLUDI si elenca e si conta a parte, senza
#                   scaricarlo. Poi guarda se pCloud dà lo SHA-256 lato server (la regione EU sì:
#                   lo chiede per 3 file e lo confronta con quello dei file scaricati).
#   2 sciolti       lo SHA-256 di ogni foto e video sciolto: lato server se pCloud lo dà, se no
#                   scaricandoli in streaming (rclone hashsum --download, niente su disco),
#                   PARALLELI insieme (predefinito 8)
#   3 zip           ogni zip in LAVORO/confronto/tmp, ZIP_PARALLELI insieme (predefinito 3): se ne
#                   estraggono le foto e i video, se ne calcola lo SHA-256, poi la copia si
#                   cancella. Gli zip rovinati si segnano come tali.
#                   Un file che era nell'elenco ma non si scarica perché pCloud dice che non c'è
#                   (directory o object not found, anche sulla cartella che lo contiene, e un
#                   rclone lsf del percorso esatto lo conferma) si segna in confronto/assenti.tsv
#                   e si va avanti: nel rapporto è "sparito", non più su pCloud (era nell'elenco).
#                   Gli altri errori di scaricamento (rete, timeout, spazio) fermano il passo.
#   4 fototimeline  da MongoDB su server2, in sola lettura (find), hash e percorso di ogni scheda;
#                   l'elenco dei file in DESTINAZIONE (lifetime:FotoTimeline); CAMPIONE file
#                   (predefinito 20) riscaricati da LifetimeCloud: il loro SHA-256 dev'essere
#                   quello della scheda
#   5 confronto     ogni foto e video di pCloud: trovato (il suo hash è in una scheda) o mancante,
#                   col perché quando si sa. In LAVORO/confronto-rapporto.tsv una riga per file,
#                   in LAVORO/confronto-riepilogo.txt i conti.
#
# Sola lettura ovunque: su pCloud e LifetimeCloud solo lsf, hashsum e copyto verso una cartella
# temporanea del PC; nel DB solo find. Niente si cancella, sposta o scrive fuori da LAVORO.
#
# Ripresa: quello già calcolato sta in LAVORO/confronto/ (sha-sciolti.tsv, sha-zip.tsv), con
# chiave percorso, dimensione e data di modifica: un rilancio non riscarica niente di quello
# già fatto, e un file cambiato su pCloud (altra dimensione o data) si rifà. Dopo un'interruzione
# (ferma, PC spento, errore): "confronta-tutto.sh da 2" (o da 3, se gli sciolti erano finiti).
#
# I mancanti hanno un motivo (colonna 3 del rapporto):
#   raccolto-non-indicizzato  in impronte.tsv e su LifetimeCloud, senza scheda: Indicizza archivio
#   raccolto-non-caricato     in impronte.tsv ma non su LifetimeCloud: foto-da-pcloud.sh carica
#   da-controllare            in RIMASTI, lasciato apposta (zip con altri file, non letto, rovinato)
#   non-letto                 raccogli l'ha visto ma exiftool non l'ha letto (da-controllare.txt)
#   zip-rovinato              dentro uno zip che unzip non apre del tutto
#   nello-zip-non-letto       in uno zip raccolto, ma exiftool non l'ha letto
#   nuovo-dopo-scarica        raccogli non l'ha mai visto e su pCloud ha una data dopo lo scarica
#   mai-visto                 raccogli non l'ha mai visto, e la data è di prima: da capire
#   cambiato-dopo-raccogli    raccogli l'ha preso, ma ora su pCloud il contenuto è un altro
#   hash-non-letto            pCloud non l'ha dato e lo scaricamento non è riuscito: rilancia
# e i "non più su pCloud" (esito "sparito", motivo "non-piu-su-pcloud"): nell'elenco del passo 1,
# ma pCloud dice che non ci sono (assenti.tsv). Spiegati, contati a parte; un rilancio li riprova
# una volta (sciolti al passo 2, zip al 3) e, se pCloud dice ancora che non ci sono, restano così.
# e i "probabili, contenuto diverso" (esito "probabile"): stesso nome e stessa data di una scheda
# (o lo stesso percorso in ordinate/), hash diverso. Sopra RICOMPRESSI_SOPRA byte (20 GB) un
# video così è uno di quelli ricompressi in H.265 prima di carica: "ricompresso-h265", spiegato.
# Le versioni compatibili dei video (.compatibili/<id>.mp4) e le viste degli HEIC non cambiano
# l'originale né la scheda: l'hash resta quello del file caricato (vedi PR e DEPLOY.md).
# Esce con 3 se c'è qualcosa di non spiegato (mai-visto, cambiato, probabile, hash non letto,
# zip non confrontati, schede senza file, campione che non torna).
#
# Altre variabili:
#   HASH_PCLOUD=auto|server|scarica   come avere lo SHA-256 degli sciolti (predefinito auto)
#   FT_SSH=server2  FT_MONGO=fototimeline-mongo-1  FT_DB_NOME=fototimeline  FT_COLLEZIONE=foto
#   FT_DB_FILE=percorso   un elenco già esportato (stesso formato) invece di chiederlo a server2
#   CAMPIONE=0            nessun file riscaricato da LifetimeCloud
#
# Serve: rclone, unzip, sha256sum, flock, ssh (sudo apt install unzip rclone util-linux openssh-client).
set -euo pipefail

SE="$(readlink -f "${BASH_SOURCE[0]}")"
QUI="$(dirname "$SE")"
LAVORO="${LAVORO:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
# Quello dato sulla riga di comando vale più di quello ricordato da foto-da-pcloud.sh.
DATI_RADICE="${RADICE:-}" DATI_ARCHIVIO="${ARCHIVIO:-}" DATI_DESTINAZIONE="${DESTINAZIONE:-}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}" DATI_PCLOUD="${PCLOUD:-}" DATI_RIMASTI="${RIMASTI:-}"
DATI_PARALLELI="${PARALLELI:-}"
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
RADICE="${DATI_RADICE:-${RADICE:-}}"
ARCHIVIO="${DATI_ARCHIVIO:-${ARCHIVIO:-}}"
DESTINAZIONE="${DATI_DESTINAZIONE:-${DESTINAZIONE:-lifetime:FotoTimeline}}"
[ "$DATI_ESCLUDI" != __nessuno__ ] && ESCLUDI="$DATI_ESCLUDI"
ESCLUDI="${ESCLUDI:-}"
PCLOUD="${DATI_PCLOUD:-${PCLOUD:-}}"
RIMASTI="${DATI_RIMASTI:-${RIMASTI:-}}"
[ "$RADICE" != / ] && RADICE="${RADICE%/}"
ARCHIVIO="${ARCHIVIO%/}"
if [ -n "$RADICE" ]; then
    RIMASTI="${RIMASTI:-Da controllare}"
    case "$RIMASTI" in /*) ;; *) RIMASTI="${RADICE%/}/$RIMASTI" ;; esac
    [ "$RIMASTI" != / ] && RIMASTI="${RIMASTI%/}"
fi
RCLONE="${RCLONE:-rclone}"
export TZ="${TZ:-Europe/Rome}"

# Le estensioni di foto e video, errore, richiede e rc: quelli di lib-foto.sh.
# shellcheck source=lib-foto.sh
. "$QUI/lib-foto.sh"
PARALLELI="${DATI_PARALLELI:-8}"
ZIP_PARALLELI="${ZIP_PARALLELI:-3}"
HASH_PCLOUD="${HASH_PCLOUD:-auto}"
RICOMPRESSI_SOPRA="${RICOMPRESSI_SOPRA:-20000000000}"
CAMPIONE="${CAMPIONE:-20}"
FT_SSH="${FT_SSH:-server2}"
FT_MONGO="${FT_MONGO:-fototimeline-mongo-1}"
FT_DB_NOME="${FT_DB_NOME:-fototimeline}"
FT_COLLEZIONE="${FT_COLLEZIONE:-foto}"
FT_DB_FILE="${FT_DB_FILE:-}"
# Un elenco di pCloud più giovane di così (da "stima") non si rifà alla partenza.
FRESCO_ORE="${FRESCO_ORE:-6}"

CONF="$LAVORO/confronto"
ELENCO="$CONF/pcloud.tsv"            # dimensione \t data \t percorso (relativo a RADICE)
OGGETTI="$CONF/oggetti.tsv"          # sciolto|zip \t normale|rimasti|escluso \t dimensione \t data \t percorso
MODO="$CONF/modo-hash"               # server | scarica
CACHE_S="$CONF/sha-sciolti.tsv"      # sha256 \t dimensione \t data \t percorso
CACHE_Z="$CONF/sha-zip.tsv"          # M \t dim zip \t data zip \t zip \t sha256 \t dim \t giorno \t membro
                                     # F \t dim zip \t data zip \t zip \t esito unzip \t media \t ._ dei Mac
ASSENTI="$CONF/assenti.tsv"          # sciolto|zip \t dimensione \t data \t percorso \t quando: nell'elenco, non su pCloud
DB="$CONF/fototimeline-db.tsv"       # id \t hash \t dimensione \t giorno \t video \t conversione \t nome \t percorso
                                     # (e DB.ok: righe \t quando, scritto solo se l'elenco è intero)
LT="$CONF/lifetime.tsv"              # dimensione \t percorso in DESTINAZIONE
CAMPIONATI="$CONF/campione.tsv"      # esito \t hash della scheda \t hash del file \t percorso
PIANO="$CONF/piano"                  # passo \t inizio \t quanti \t byte da scaricare
FATTI="$CONF/fatti"                  # quanti \t byte, una riga per zip o blocco finito
STIMA="$CONF/stima.txt"
PID="$CONF/pid"
TMPC="$CONF/tmp"
RAPPORTO="$LAVORO/confronto-rapporto.tsv"
RIEPILOGO="$LAVORO/confronto-riepilogo.txt"
REGISTRO="$LAVORO/confronto.log"
PASSO="$LAVORO/confronto.passo"
NOMI=(elenca sciolti zip fototimeline confronto)
TOTALE=${#NOMI[@]}
IMPRONTE="$LAVORO/impronte.tsv" FONTI="$LAVORO/fonti.tsv" DA_CONTROLLARE="$LAVORO/da-controllare.txt"

ora() { date '+%d/%m %H:%M'; }
gb() { awk -v b="$1" 'BEGIN { printf "%.1f GB", b / 1e9 }'; }
# Il percorso rclone di un file relativo a RADICE (come remoto_di di foto-da-pcloud.sh).
remoto() { case "$PCLOUD" in *:) printf '%s%s' "$PCLOUD" "$1" ;; *) printf '%s/%s' "$PCLOUD" "$1" ;; esac; }
rel_di() { local r="${1#"$RADICE"/}"; [ "$r" != "$1" ] && printf '%s' "$r"; return 0; }
controlla_impostazioni() {
    [ -n "$RADICE" ] || errore "manca RADICE: prima foto-da-pcloud.sh anteprima (o RADICE=/mnt/p davanti)"
    [ -n "$PCLOUD" ] || errore "manca PCLOUD in $IMPOSTAZIONI (per esempio PCLOUD=pcloud: davanti)"
    [[ "$PARALLELI" =~ ^[1-9][0-9]*$ ]] || errore "PARALLELI dev'essere un numero da 1 in su (è '$PARALLELI')"
    [[ "$ZIP_PARALLELI" =~ ^[1-9][0-9]*$ ]] || errore "ZIP_PARALLELI dev'essere un numero da 1 in su (è '$ZIP_PARALLELI')"
    mkdir -p "$CONF"
}
# Gira il giro in sottofondo? Dal pid scritto da esegui, mai con pgrep (troverebbe sé stesso).
in_corso() {
    local p
    [ -f "$PID" ] || return 1
    p="$(cat "$PID")"
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null || return 1
    # Senza "| grep -q": con pipefail, grep che esce al primo colpo farebbe fallire tr (SIGPIPE).
    p="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null)" || return 1
    [[ "$p" == *"confronta-tutto.sh esegui"* ]]
}
# Una riga di rclone hashsum ("sha256  percorso"), senza {64}: il mawk di Ubuntu 22.04 non lo sa.
AWK_SHA='function e_sha(s) { return length(s) >= 67 && substr(s, 1, 64) !~ /[^0-9a-f]/ && substr(s, 65, 2) == "  " }'

# assente REL: vero se pCloud dice che il file non c'è, lui o la cartella che lo contiene. Si
# chiede con un rclone lsf del percorso esatto: "directory not found" (uscita 3) o "object not
# found" (4). Un altro errore (rete, timeout, 5xx) non è un'assenza: falso, e chi chiama si ferma.
# Succede con i file "fantasma" di pCloud: nell'elenco ricorsivo, ma né lsf né copyto li trovano.
assente() {
    local st=0 out
    out="$(rc lsf --files-only "$(remoto "$1")" 2>&1 < /dev/null)" || st=$?
    case "$st" in
        0) return 1 ;;
        3|4) return 0 ;;
    esac
    printf '%s\n' "$out" | grep -Eq '^.*Failed to lsf.*(directory not found|object not found)'
}
# segna_assente TIPO DIM DATA REL: in assenti.tsv (con flock: gli zip sono più d'uno insieme).
segna_assente() {
    { flock 9; printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$(date +%s)" >> "$ASSENTI"; } 9>> "$ASSENTI.lock"
}
# Quanti dei da fare (dim \t data \t percorso, nel file $2) sono in assenti.tsv per il tipo $1.
assenti_fra() {
    touch "$ASSENTI"
    awk -F'\t' -v t="$1" 'FILENAME == ARGV[1] { if ($1 == t) a[$2 SUBSEP $3 SUBSEP $4] = 1; next }
        (($1 SUBSEP $2 SUBSEP $3) in a) { n++ } END { print n + 0 }' "$ASSENTI" "$2"
}

# --- 1. elenca ---------------------------------------------------------------------------
# I filtri di rclone: le stesse esclusioni di foto-da-pcloud.sh, ma RIMASTI e ESCLUDI dentro.
filtri() {
    local rel x
    if rel="$(rel_di "$ARCHIVIO")" && [ -n "$rel" ]; then
        printf -- '- /%s/**\n' "$(printf '%s' "$rel" | sed 's/[][\\*?{}]/\\&/g')"
    fi
    printf -- '- Crypto Folder/**\n- System Volume Information/**\n- $RECYCLE.BIN/**\n- .Trash*/**\n- .pcloud*/**\n'
    printf -- '+ *.zip\n'
    for x in "${ESTENSIONI[@]}"; do printf -- '+ *.%s\n' "$x"; done
    printf -- '- *\n'
}

# Ogni file dell'elenco: sciolto o zip, e normale, in RIMASTI o in ESCLUDI.
classifica() {
    local escludi="" x rimasti
    local IFS=:
    for x in $ESCLUDI; do [ -n "$x" ] && escludi+="${x%/}/"$'\n'; done
    unset IFS
    rimasti="$(rel_di "$RIMASTI")" || true
    awk -F'\t' -v OFS='\t' -v escludi="$escludi" -v rimasti="$rimasti" '
        BEGIN { n = split(escludi, E, "\n") }
        {
            p = $3; classe = "normale"
            for (i = 1; i <= n; i++) if (E[i] != "" && substr(p, 1, length(E[i])) == E[i]) classe = "escluso"
            if (classe == "normale" && rimasti != "" && substr(p, 1, length(rimasti) + 1) == rimasti "/") classe = "rimasti"
            tipo = (tolower(p) ~ /\.zip$/) ? "zip" : "sciolto"
            print tipo, classe, $1, $2, p
        }' "$ELENCO" > "$OGGETTI"
}

elenca() {
    richiede "$RCLONE"
    controlla_impostazioni
    filtri > "$CONF/filtri.txt"
    echo "Elenco i file di $PCLOUD (salto ${ARCHIVIO:+$(rel_di "$ARCHIVIO"), }Crypto Folder e le cartelle di sistema; confronto anche ${RIMASTI#"$RADICE"/})…"
    rc lsf -R --files-only --format stp --separator $'\t' --filter-from "$CONF/filtri.txt" --ignore-case "$PCLOUD" \
        > "$CONF/pcloud.nuovo" || errore "non riesco a elencare $PCLOUD: rilancia"
    # Un nome con un tab dentro romperebbe le colonne: si dice e si salta.
    local strani
    strani="$(awk -F'\t' 'NF != 3' "$CONF/pcloud.nuovo" | wc -l)"
    [ "$strani" -gt 0 ] && echo "Attenzione: $strani file con un tab nel nome, non li confronto (sono in $CONF/nomi-strani.txt)"
    awk -F'\t' 'NF != 3' "$CONF/pcloud.nuovo" > "$CONF/nomi-strani.txt"
    awk -F'\t' 'NF == 3' "$CONF/pcloud.nuovo" > "$ELENCO"
    rm -f "$CONF/pcloud.nuovo"
    classifica
    scegli_modo
    stima | tee "$STIMA"
}

# Lo SHA-256 lato server: si chiede per i 3 sciolti più piccoli e si confronta con quello dei
# file scaricati. Se non c'è (regione US, rclone vecchio) o non torna, si scarica tutto.
scegli_modo() {
    case "$HASH_PCLOUD" in
        server|scarica) echo "$HASH_PCLOUD" > "$MODO"; echo "SHA-256 degli sciolti: $HASH_PCLOUD (HASH_PCLOUD)"; return ;;
        auto) ;;
        *) errore "HASH_PCLOUD dev'essere auto, server o scarica (è '$HASH_PCLOUD')" ;;
    esac
    awk -F'\t' '$1 == "sciolto" && $2 != "escluso" && $3 > 0 { print $3 "\t" $5 }' "$OGGETTI" \
        | sort -n | awk -F'\t' 'NR <= 3 { print $2 }' > "$CONF/prova.lista"
    if [ ! -s "$CONF/prova.lista" ]; then echo scarica > "$MODO"; return; fi
    local server scaricati
    server="$(rc hashsum sha256 --files-from-raw "$CONF/prova.lista" "$PCLOUD" 2>/dev/null | grep -E '^[0-9a-f]{64}  ' | sort)" || server=""
    scaricati="$(rc hashsum sha256 --download --files-from-raw "$CONF/prova.lista" "$PCLOUD" 2>/dev/null | grep -E '^[0-9a-f]{64}  ' | sort)" || scaricati=""
    if [ -n "$server" ] && [ "$server" = "$scaricati" ] && [ "$(printf '%s\n' "$server" | wc -l)" -eq "$(wc -l < "$CONF/prova.lista")" ]; then
        echo server > "$MODO"
        echo "pCloud dà lo SHA-256 lato server (provato su $(wc -l < "$CONF/prova.lista") file, uguale a quello dei file scaricati): gli sciolti non si scaricano."
    else
        echo scarica > "$MODO"
        echo "pCloud non dà lo SHA-256 lato server (o non torna con i file scaricati): gli sciolti si scaricano in streaming."
    fi
}

# Quello che manca nelle cache: sciolti (da-fare-sciolti.tsv) e zip (da-fare-zip.tsv).
da_fare() {
    touch "$CACHE_S" "$CACHE_Z"
    awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { fatto[$2 SUBSEP $3 SUBSEP $4] = 1; next }
        $1 == "sciolto" && $2 != "escluso" && !(($3 SUBSEP $4 SUBSEP $5) in fatto) { print $3, $4, $5 }' \
        "$CACHE_S" "$OGGETTI" > "$CONF/da-fare-sciolti.tsv"
    awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { if ($1 == "F") fatto[$2 SUBSEP $3 SUBSEP $4] = 1; next }
        $1 == "zip" && $2 != "escluso" && !(($3 SUBSEP $4 SUBSEP $5) in fatto) { print $3, $4, $5 }' \
        "$CACHE_Z" "$OGGETTI" > "$CONF/da-fare-zip.tsv"
}

somma() { awk -F'\t' '{ s += $1 } END { printf "%.0f", s }' "$1"; }

stima() {
    da_fare
    local modo n_s b_s n_z b_z n_e b_e tot
    modo="$(cat "$MODO")"
    read -r n_e b_e < <(awk -F'\t' '$2 == "escluso" { n++; s += $3 } END { printf "%d %.0f\n", n, s }' "$OGGETTI")
    n_s="$(wc -l < "$CONF/da-fare-sciolti.tsv")"; b_s="$(somma "$CONF/da-fare-sciolti.tsv")"
    n_z="$(wc -l < "$CONF/da-fare-zip.tsv")";     b_z="$(somma "$CONF/da-fare-zip.tsv")"
    echo "Su pCloud: $(awk -F'\t' '$1 == "sciolto"' "$OGGETTI" | wc -l) foto e video sciolti e $(awk -F'\t' '$1 == "zip"' "$OGGETTI" | wc -l) zip ($(gb "$(somma <(cut -f3 "$OGGETTI"))"))."
    echo "  di cui in ${RIMASTI#"$RADICE"/}: $(awk -F'\t' '$2 == "rimasti"' "$OGGETTI" | wc -l); in ESCLUDI (contati, non confrontati): $n_e file, $(gb "$b_e")"
    tot="$b_z"
    if [ "$modo" = scarica ]; then
        tot=$((b_s + b_z))
        echo "Da fare: $n_s sciolti da scaricare ($(gb "$b_s")) e $n_z zip ($(gb "$b_z"))."
    else
        echo "Da fare: $n_s sciolti (SHA-256 lato server, niente da scaricare) e $n_z zip ($(gb "$b_z"))."
    fi
    echo "Scaricherà più o meno $(gb "$tot") (quello già fatto in un giro di prima non si riscarica)."
    local a_s a_z
    a_s="$(assenti_fra sciolto "$CONF/da-fare-sciolti.tsv")"; a_z="$(assenti_fra zip "$CONF/da-fare-zip.tsv")"
    if [ $((a_s + a_z)) -gt 0 ]; then
        echo "  di cui $a_s sciolti e $a_z zip non più su pCloud a un giro di prima (erano nell'elenco): si riprovano una volta."
    fi
    echo "Sul PC servono al massimo $ZIP_PARALLELI zip insieme, più le loro foto estratte (spazio libero: $(df -h --output=avail "$LAVORO" | tail -1 | tr -d ' '))."
}

# --- 2. sciolti --------------------------------------------------------------------------
# A blocchi (al massimo 500 file o 2 GB, lato server 2000 file): ogni blocco finito va nella
# cache. Un blocco interrotto si recupera al giro dopo da blocco.out (le righe finite).
unisci_blocco() {
    [ -f "$CONF/blocco.tsv" ] && [ -f "$CONF/blocco.out" ] || return 0
    awk -F'\t' -v OFS='\t' "$AWK_SHA"'
        FILENAME == ARGV[1] { chi[$3] = $1 SUBSEP $2; next }
        e_sha($0) {
            sha = substr($0, 1, 64); p = substr($0, 67)
            if (p in chi) { split(chi[p], k, SUBSEP); print sha, k[1], k[2], p }
        }' "$CONF/blocco.tsv" "$CONF/blocco.out" >> "$CACHE_S"
    rm -f "$CONF/blocco.out"
}

sciolti() {
    richiede "$RCLONE" flock
    controlla_impostazioni
    [ -f "$OGGETTI" ] && [ -f "$MODO" ] || errore "prima il passo 1: $0 stima"
    unisci_blocco
    rm -f "$CONF/blocco.tsv"
    da_fare
    local modo n b
    modo="$(cat "$MODO")"
    n="$(wc -l < "$CONF/da-fare-sciolti.tsv")"; b="$(somma "$CONF/da-fare-sciolti.tsv")"
    [ "$modo" = server ] && b=0
    printf 'sciolti\t%s\t%s\t%s\n' "$(date +%s)" "$n" "$b" > "$PIANO"
    : > "$FATTI"
    echo "$n foto e video sciolti da fare ($modo$([ "$modo" = scarica ] && echo ", $(gb "$b")"))"
    blocchi "$modo" < "$CONF/da-fare-sciolti.tsv"
    # Lato server, quelli senza hash (o con un errore) si riprovano scaricandoli.
    if [ "$modo" = server ]; then
        da_fare
        n="$(wc -l < "$CONF/da-fare-sciolti.tsv")"
        if [ "$n" -gt 0 ]; then
            echo "$n file senza SHA-256 dal server: li scarico"
            blocchi scarica < "$CONF/da-fare-sciolti.tsv"
        fi
    fi
    da_fare
    # Quelli non riusciti: pCloud dice che non ci sono? Allora sono spariti dopo l'elenco.
    local dim data rel spariti=0
    while IFS=$'\t' read -r dim data rel; do
        if assente "$rel"; then
            segna_assente sciolto "$dim" "$data" "$rel"
            spariti=$((spariti + 1))
            echo "  $rel: non più su pCloud (era nell'elenco), lo segno e vado avanti"
        fi
    done < "$CONF/da-fare-sciolti.tsv"
    n=$(( $(wc -l < "$CONF/da-fare-sciolti.tsv") - spariti ))
    echo "Fatto: $(wc -l < "$CACHE_S") sciolti con il loro SHA-256$([ "$spariti" -gt 0 ] && echo "; $spariti non più su pCloud (erano nell'elenco): nel rapporto come sparito")$([ "$n" -gt 0 ] && echo "; $n non riusciti: nel rapporto come hash-non-letto (rilancia da 2)")."
}

blocchi() {
    local modo="$1" dim data rel quanti=0 byte=0 max=500 limite=2000000000
    local -a scarica=()
    [ "$modo" = scarica ] && scarica=(--download)
    [ "$modo" = server ] && max=2000 limite=999999999999999
    : > "$CONF/blocco.tsv"
    while IFS=$'\t' read -r dim data rel; do
        printf '%s\t%s\t%s\n' "$dim" "$data" "$rel" >> "$CONF/blocco.tsv"
        quanti=$((quanti + 1)); byte=$((byte + dim))
        if [ "$quanti" -ge "$max" ] || [ "$byte" -ge "$limite" ]; then
            un_blocco "$quanti" "$byte"
            quanti=0 byte=0
            : > "$CONF/blocco.tsv"
        fi
    done
    if [ "$quanti" -gt 0 ]; then un_blocco "$quanti" "$byte"; fi
    rm -f "$CONF/blocco.tsv"
}
# Vede modo e scarica di blocchi.
un_blocco() {
    cut -f3 "$CONF/blocco.tsv" > "$CONF/blocco.lista"
    rc hashsum sha256 "${scarica[@]}" --checkers "$PARALLELI" --files-from-raw "$CONF/blocco.lista" "$PCLOUD" \
        > "$CONF/blocco.out" < /dev/null 2> "$CONF/blocco.err" \
        || { grep -v 'NOTICE' "$CONF/blocco.err" | sed -n '1,5p' >&2 || true; }
    unisci_blocco
    [ "$modo" = scarica ] || set -- "$1" 0
    printf '%s\t%s\n' "$1" "$2" >> "$FATTI"
    echo "  $(awk -F'\t' '{ n += $1 } END { print n + 0 }' "$FATTI") sciolti fatti ($(ora))"
}

# --- 3. zip ------------------------------------------------------------------------------
zip_tutti() {
    richiede "$RCLONE" unzip sha256sum flock
    controlla_impostazioni
    [ -f "$OGGETTI" ] || errore "prima il passo 1: $0 stima"
    # Quello che un giro interrotto ha lasciato in tmp/ non serve: nessun altro giro è in corso.
    rm -rf "$TMPC"
    mkdir -p "$TMPC"
    da_fare
    local n b
    n="$(wc -l < "$CONF/da-fare-zip.tsv")"; b="$(somma "$CONF/da-fare-zip.tsv")"
    printf 'zip\t%s\t%s\t%s\n' "$(date +%s)" "$n" "$b" > "$PIANO"
    : > "$FATTI"
    echo "$n zip da scaricare e aprire ($(gb "$b")), $ZIP_PARALLELI insieme"
    [ "$n" -gt 0 ] || return 0
    local stato=0
    tr '\n' '\0' < "$CONF/da-fare-zip.tsv" | xargs -0 -r -n 1 -P "$ZIP_PARALLELI" "$SE" _zip || stato=$?
    rm -rf "$TMPC"
    da_fare
    # Gli zip che pCloud dice di non avere (segnati da uno_zip in questo giro) sono spiegati.
    local spariti
    spariti="$(assenti_fra zip "$CONF/da-fare-zip.tsv")"
    n=$(( $(wc -l < "$CONF/da-fare-zip.tsv") - spariti ))
    if [ "$stato" -ne 0 ] || [ "$n" -gt 0 ]; then
        errore "$n zip non sono riusciti (vedi sopra): rilancia $0 da 3, quelli fatti non si rifanno"
    fi
    echo "Fatto: $(grep -c '^F' "$CACHE_Z" || true) zip aperti$([ "$spariti" -gt 0 ] && echo "; $spariti non più su pCloud (erano nell'elenco): nel rapporto come sparito, elenco in $ASSENTI")."
}

# Uno zip (dim \t data \t percorso), lanciato da xargs: scarica, estrae solo foto e video,
# calcola gli SHA-256, scrive nella cache tutto lo zip in una volta (con flock: ce ne sono
# ZIP_PARALLELI insieme), cancella la copia. Esce con 255 (xargs si ferma) se manca spazio.
uno_zip() {
    local zdim zdata zrel d stato=0 libero
    IFS=$'\t' read -r zdim zdata zrel <<< "$1"
    d="$(mktemp -d "$TMPC/z.XXXXXX")"
    ZIP_DIR="$d"
    trap 'rm -rf "$ZIP_DIR"' EXIT
    trap 'exit 143' TERM INT
    libero="$(df -P -B1 "$TMPC" | awk 'NR == 2 { print $4 }')"
    if [ "$libero" -lt $((zdim * 2 + 1000000000)) ]; then
        echo "Spazio finito sul PC per $zrel ($(gb "$zdim")): libera spazio o abbassa ZIP_PARALLELI, poi $0 da 3" >&2
        exit 255
    fi
    if ! rc copyto "$(remoto "$zrel")" "$d/z.zip" < /dev/null 2> "$d/copyto.err"; then
        # Non c'è più (lui o la sua cartella): si segna e si va avanti. Altrimenti è un errore vero.
        if assente "$zrel"; then
            segna_assente zip "$zdim" "$zdata" "$zrel"
            { flock 9; printf '1\t%s\n' "$zdim" >> "$FATTI"; } 9>> "$CACHE_Z.lock"
            echo "  [zip $(wc -l < "$FATTI")] $zrel: non più su pCloud (era nell'elenco), lo segno e vado avanti"
            exit 0
        fi
        grep -v NOTICE "$d/copyto.err" | tail -n 3 >&2 || true
        echo "non riesco a scaricare $zrel" >&2; exit 1
    fi
    if [ "$(stat -c %s "$d/z.zip")" != "$zdim" ]; then
        echo "$zrel è cambiato su pCloud mentre lo scaricavo: rilancia da 1" >&2; exit 1
    fi
    # Solo foto e video (-C: senza badare a maiuscole): niente spazio per i documenti.
    local -a quali=()
    local e
    for e in "${ESTENSIONI[@]}"; do quali+=("*.$e"); done
    unzip -qq -o -C "$d/z.zip" "${quali[@]}" -d "$d/in" < /dev/null > "$d/unzip.txt" 2>&1 || stato=$?
    rm -f "$d/z.zip"
    # 11: nessun file corrisponde (uno zip senza foto). 1: solo avvisi.
    [ "$stato" -eq 11 ] && stato=0
    mkdir -p "$d/in"
    local -A SHA=() META=()
    local riga p mac=0 n=0
    while IFS= read -r -d '' riga; do
        SHA["${riga#*  }"]="${riga%%  *}"
    done < <(cd "$d/in" && find . -type f ! -path '*/__MACOSX/*' ! -name '._*' -print0 | sha256_z)
    while IFS= read -r -d '' riga; do
        p="${riga#*$'\t'}"; p="${p#*$'\t'}"
        META["./$p"]="${riga%$'\t'"$p"}"
    done < <(cd "$d/in" && find . -type f ! -path '*/__MACOSX/*' ! -name '._*' -printf '%s\t%TY-%Tm-%Td\t%P\0')
    mac="$(cd "$d/in" && find . -type f \( -path '*/__MACOSX/*' -o -name '._*' \) | wc -l)"
    {
        for p in "${!SHA[@]}"; do
            n=$((n + 1))
            riga="${p#./}"; riga="${riga//$'\t'/ }"; riga="${riga//$'\n'/ }"
            printf 'M\t%s\t%s\t%s\t%s\t%s\t%s\n' "$zdim" "$zdata" "$zrel" "${SHA[$p]}" "${META[$p]:-0$'\t'}" "$riga"
        done
        printf 'F\t%s\t%s\t%s\t%s\t%s\t%s\n' "$zdim" "$zdata" "$zrel" "$stato" "$n" "$mac"
    } > "$d/blocco"
    { flock 9; cat "$d/blocco" >> "$CACHE_Z"; printf '1\t%s\n' "$zdim" >> "$FATTI"; } 9>> "$CACHE_Z.lock"
    local esito="$n foto e video"
    [ "$stato" -gt 1 ] && esito="$esito, rovinato (unzip $stato)"
    echo "  [zip $(wc -l < "$FATTI")] $zrel: $esito"
}

# --- 4. fototimeline ---------------------------------------------------------------------
# Lo script per mongosh, in sola lettura: find di tutte le schede, una riga per scheda, e in
# fondo quante sono (per sapere che l'elenco è intero).
js_db() {
    cat <<FINE
var c = db.getSiblingDB("$FT_DB_NOME").getCollection("$FT_COLLEZIONE"), n = 0;
function campo(x) { return x === undefined || x === null ? "" : String(x).replace(/[\t\r\n]/g, " "); }
c.find({}, {hash: 1, dimensione: 1, giorno: 1, video: 1, conversione: 1, nomeOriginale: 1, percorso: 1}).batchSize(5000).forEach(function (f) {
  n++;
  print([f._id, f.hash, f.dimensione, f.giorno, f.video, f.conversione, f.nomeOriginale, f.percorso].map(campo).join("\t"));
});
print("#fine\t" + n + "\t" + c.countDocuments({}));
FINE
}

esporta_db() {
    if [ -n "$FT_DB_FILE" ]; then
        echo "Schede di FotoTimeline da $FT_DB_FILE (FT_DB_FILE)"
        cp "$FT_DB_FILE" "$CONF/db.nuovo"
    else
        richiede ssh base64
        echo "Schede di FotoTimeline da $FT_SSH ($FT_MONGO, db $FT_DB_NOME, collezione $FT_COLLEZIONE, solo find)…"
        local js
        js="$(js_db | base64 -w0)"
        # Lo script passa in base64: niente virgolette da proteggere fra ssh, docker e sh.
        # shellcheck disable=SC2029
        ssh -o BatchMode=yes "$FT_SSH" "docker exec $FT_MONGO sh -c 'mongosh --quiet --norc -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin $FT_DB_NOME --eval \"\$(echo $js | base64 -d)\"'" \
            < /dev/null > "$CONF/db.nuovo" || errore "non riesco a leggere il DB su $FT_SSH: ssh $FT_SSH funziona? (vedi DEPLOY.md)"
    fi
    local fine righe n contati
    fine="$(grep '^#fine' "$CONF/db.nuovo" | tail -1)" || true
    [ -n "$fine" ] || errore "l'elenco delle schede è a metà (manca #fine): rilancia $0 da 4"
    n="$(printf '%s' "$fine" | cut -f2)"; contati="$(printf '%s' "$fine" | cut -f3)"
    # fototimeline-db.tsv.ok (righe \t quando) solo con l'elenco intero: raccogli-rimasti.sh
    # cancella da pCloud quello che c'è qui, e un elenco a metà non deve valere.
    rm -f "$DB.ok"
    awk -F'\t' '!/^#fine/ && NF == 8' "$CONF/db.nuovo" > "$DB"
    righe="$(wc -l < "$DB")"
    rm -f "$CONF/db.nuovo"
    [ "$righe" = "$n" ] || errore "l'elenco delle schede è a metà ($righe righe su $n): rilancia $0 da 4"
    printf '%s\t%s\n' "$righe" "$(date +%s)" > "$DB.ok"
    [ "$n" = "$contati" ] || echo "Attenzione: le schede sono cambiate durante l'esportazione ($n lette, ora $contati): c'è un'indicizzazione in corso?"
    echo "  $n schede"
}

fototimeline() {
    richiede "$RCLONE"
    controlla_impostazioni
    esporta_db
    echo "Elenco i file di $DESTINAZIONE…"
    rc lsf -R --files-only --format sp --separator $'\t' "$DESTINAZIONE" > "$CONF/lt.nuovo" \
        || errore "non riesco a elencare $DESTINAZIONE: rilancia $0 da 4"
    mv "$CONF/lt.nuovo" "$LT"
    echo "  $(wc -l < "$LT") file"
    campione
}

# CAMPIONE schede a caso col loro file su LifetimeCloud (sotto i 50 MB): si riscaricano e lo
# SHA-256 del file dev'essere quello della scheda. Così l'abbinamento per percorso che fa il
# confronto è provato sul contenuto vero.
campione() {
    : > "$CAMPIONATI"
    [ "$CAMPIONE" -gt 0 ] 2>/dev/null || return 0
    awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { c[$2] = $1; next }
        ($8 in c) && c[$8] == $3 && $3 < 50000000 && $2 ~ /^[0-9a-f]+$/ { print $2, $8 }' "$LT" "$DB" \
        | shuf -n "$CAMPIONE" > "$CONF/campione.scelti" || true
    [ -s "$CONF/campione.scelti" ] || return 0
    cut -f2 "$CONF/campione.scelti" > "$CONF/campione.lista"
    echo "Riscarico $(wc -l < "$CONF/campione.lista") file a caso da $DESTINAZIONE e ne ricontrollo lo SHA-256…"
    rc hashsum sha256 --download --checkers 4 --files-from-raw "$CONF/campione.lista" "$DESTINAZIONE" \
        > "$CONF/campione.out" < /dev/null 2> "$CONF/campione.err" || true
    awk -F'\t' -v OFS='\t' "$AWK_SHA"'
        FILENAME == ARGV[1] { if (e_sha($0)) letto[substr($0, 67)] = substr($0, 1, 64); next }
        { h = ($2 in letto) ? letto[$2] : ""; print (h == $1 ? "uguale" : "DIVERSO"), $1, h, $2 }' \
        "$CONF/campione.out" "$CONF/campione.scelti" > "$CAMPIONATI"
    rm -f "$CONF/campione.out" "$CONF/campione.err" "$CONF/campione.lista" "$CONF/campione.scelti"
    echo "  $(grep -c '^uguale' "$CAMPIONATI" || true) uguali, $(grep -c '^DIVERSO' "$CAMPIONATI" || true) diversi"
}

# --- 5. confronto ------------------------------------------------------------------------
confronto() {
    controlla_impostazioni
    local f
    for f in "$OGGETTI" "$DB" "$LT"; do
        [ -f "$f" ] || errore "manca $f: prima i passi da 1 a 4 ($0)"
    done
    touch "$CACHE_S" "$CACHE_Z" "$CAMPIONATI" "$ASSENTI"
    local vuoto="$CONF/vuoto" dopo="" quando="" stato=0
    : > "$vuoto"
    if [ -f "$LAVORO/fase-scarica.ok" ]; then
        dopo="$(date -d "@$(cat "$LAVORO/fase-scarica.ok")" '+%Y-%m-%d %H:%M:%S')"
        quando="$(date -d "@$(cat "$LAVORO/fase-scarica.ok")" '+%d/%m/%Y')"
    fi
    local video=""
    for f in "${ESTENSIONI[@]}"; do case "$f" in mp4|m4v|mov) video+="$f " ;; esac; done
    awk -F'\t' -v OFS='\t' -f <(programma_confronto) \
        -v radice="$RADICE" -v rimasti="$(rel_di "$RIMASTI" || true)" -v dopo="$dopo" -v quando="$quando" \
        -v soglia="$RICOMPRESSI_SOPRA" -v video="$video" -v estensioni="${ESTENSIONI[*]}" \
        -v rapporto="$RAPPORTO" -v dest="$DESTINAZIONE" -v nome_rimasti="${RIMASTI#"$RADICE"/}" \
        f=imp "$(esiste "$IMPRONTE" "$vuoto")" f=fonti "$(esiste "$FONTI" "$vuoto")" \
        f=dc "$(esiste "$DA_CONTROLLARE" "$vuoto")" f=db "$DB" f=lt "$LT" f=cs "$CACHE_S" \
        f=cz "$CACHE_Z" f=camp "$CAMPIONATI" f=ass "$ASSENTI" f=ogg "$OGGETTI" > "$RIEPILOGO" || stato=$?
    rm -f "$vuoto"
    cat "$RIEPILOGO"
    echo
    echo "Una riga per file in $RAPPORTO (colonne: lato, esito, motivo, sha256, dimensione, percorso, nello zip, in FotoTimeline, nota)."
    # shellcheck disable=SC2016
    printf '%s\n' "Per esempio mancanti e probabili:  awk -F'\t' '\$2 == \"mancante\" || \$2 == \"probabile\"' $RAPPORTO | less -S"
    return "$stato"
}
esiste() { if [ -f "$1" ]; then printf '%s' "$1"; else printf '%s' "$2"; fi; }

programma_confronto() {
    cat <<'FINE'
function nome_di(p) { sub(/.*\//, "", p); return p }
# Il nome per il secondo abbinamento: minuscolo, senza estensione né il numero che raccogli
# aggiunge ai nomi uguali (-1, _2, " (2)").
function stelo(p,   n) {
    n = tolower(nome_di(p)); sub(/\.[^.]*$/, "", n)
    sub(/( \([0-9]+\)|-[0-9]|-[0-9][0-9]|_[0-9]|_[0-9][0-9])$/, "", n)
    return n
}
# Il giorno di un percorso dell'archivio (AAAA/MM/GG/...), "" se non è in una cartella così.
function giorno_di(p) {
    if (p ~ /^[0-9][0-9][0-9][0-9]\/[0-9][0-9]\/[0-9][0-9]\//) return substr(p, 1, 4) "-" substr(p, 6, 2) "-" substr(p, 9, 2)
    return ""
}
function e_video(p,   x) { x = tolower(p); sub(/.*\./, "", x); return (" " video) ~ (" " x " ") }
function e_media(p,   x) { x = tolower(p); if (x !~ /\./) return 0; sub(/.*\./, "", x); return (" " estensioni " ") ~ (" " x " ") }
function in_punto(p) { return p ~ /(^|\/)\./ }
function riga(lato, esito, motivo, sha, dim, perc, membro, inft, nota) {
    print lato, esito, motivo, sha, dim, perc, membro, inft, nota > rapporto
    conta[lato SUBSEP esito SUBSEP motivo]++
}
# Il secondo abbinamento, per stelo e giorno (della data su pCloud o della cartella in
# ordinate/): il percorso della scheda, o "".
function simile(p, g1, g2,   s) {
    s = stelo(p)
    if (g1 != "" && ((s SUBSEP g1) in SIMILE)) return SIMILE[s SUBSEP g1]
    if (g2 != "" && ((s SUBSEP g2) in SIMILE)) return SIMILE[s SUBSEP g2]
    return ""
}
# Esito di una foto o di un video di pCloud. tipo: sciolto o membro; fonte: il percorso sotto
# RADICE dello sciolto o dello zip (quello di prima di metti-da-parte, se è in RIMASTI).
function giudica(sha, dim, data, rel, membro, mgiorno, classe, fonte, zstato,   p, perc, cand, motivo, nota, g, inft, esito) {
    perc = radice "/" rel
    if (sha in HASH) { riga("pcloud", "trovato", "", sha, dim, perc, membro, HASH[sha], ""); TROVATI_SHA[sha] = 1; return }
    g = (membro != "") ? mgiorno : substr(data, 1, 10)
    # In impronte.tsv: raccolto. Lo stesso percorso in FotoTimeline con un altro hash, o un nome
    # simile nello stesso giorno, è un probabile; altrimenti manca la scheda o il file.
    if (sha in IMP) {
        p = IMP[sha]
        cand = (p in PERC) ? p : simile(p, giorno_di(p), g)
        if (cand != "") {
            if (dim + 0 >= soglia + 0 && e_video(perc membro) && DIM[cand] + 0 < dim + 0) {
                riga("pcloud", "probabile", "ricompresso-h265", sha, dim, perc, membro, cand, "in ordinate/ come " p ": video oltre la soglia, ricompresso prima di carica")
            } else {
                riga("pcloud", "probabile", (cand == p ? "stesso-percorso" : "stesso-nome-e-data"), sha, dim, perc, membro, cand, "raccolto come " p ", in FotoTimeline con un altro contenuto")
            }
            return
        }
        if (p in LTP) riga("pcloud", "mancante", "raccolto-non-indicizzato", sha, dim, perc, membro, "", "su " dest " come " p ": Indicizza archivio")
        else riga("pcloud", "mancante", "raccolto-non-caricato", sha, dim, perc, membro, "", "in impronte.tsv come " p ", non su " dest ": foto-da-pcloud.sh carica")
        return
    }
    # Mai raccolto: perché.
    if (classe == "rimasti") {
        motivo = "da-controllare"
        nota = (fonte in DC) ? DC[fonte] : "non in da-controllare.txt"
        if (membro != "" && zstato > 1) nota = nota "; zip rovinato (unzip " zstato ")"
        else if (membro != "") nota = nota "; nello zip, non letto da raccogli"
    } else if (membro == "") {
        if (fonte in FFILE) { motivo = (FFILE[fonte] == "no") ? "non-letto" : "cambiato-dopo-raccogli" }
        else motivo = (dopo != "" && data > dopo) ? "nuovo-dopo-scarica" : "mai-visto"
        nota = (motivo == "nuovo-dopo-scarica") ? "data su pCloud " data ", dopo lo scarica del " quando : ""
    } else {
        if (zstato > 1 || ((fonte in DC) && DC[fonte] ~ /^zip rovinato/)) motivo = "zip-rovinato"
        else if (fonte in FZIP) motivo = "nello-zip-non-letto"
        else motivo = (dopo != "" && data > dopo) ? "nuovo-dopo-scarica" : "mai-visto"
        nota = (zstato > 1) ? "unzip " zstato : ""
        if (motivo == "nuovo-dopo-scarica") nota = "zip con data " data ", dopo lo scarica del " quando
    }
    cand = simile(perc membro, g, "")
    if (cand != "") {
        esito = (dim + 0 >= soglia + 0 && e_video(perc membro) && DIM[cand] + 0 < dim + 0) ? "ricompresso-h265" : "stesso-nome-e-data"
        riga("pcloud", "probabile", esito, sha, dim, perc, membro, cand, motivo (nota != "" ? " (" nota ")" : ""))
        return
    }
    riga("pcloud", "mancante", motivo, sha, dim, perc, membro, "", nota)
}
BEGIN {
    # Quali motivi sono spiegati: tutto il resto fa uscire con 3.
    split("trovato escluso da-controllare non-letto zip-rovinato nello-zip-non-letto raccolto-non-indicizzato raccolto-non-caricato nuovo-dopo-scarica ricompresso-h265 zip-senza-foto", s, " ")
    for (i in s) SPIEGATO[s[i]] = 1
    printf "" > rapporto
}
f == "imp"   { IMP[$1] = $2; IMPP[$2] = $1; next }
f == "fonti" { if ($1 == "file") FFILE[$2] = $3; else FZIP[$2] = $3; next }
f == "dc" {
    if ($0 ~ /^zip con [0-9]+ altri file, resta [^:]*: /) { p = $0; sub(/^[^:]*: /, "", p); DC[p] = "zip con altri file" }
    else if ($0 ~ /^non letto: /) { p = substr($0, 12); DC[p] = "non letto" }
    else if ($0 ~ /^zip rovinato \(unzip [0-9]+\): /) { p = $0; sub(/^[^:]*: /, "", p); DC[p] = "zip rovinato" }
    next
}
f == "db" {
    nschede++
    if ($2 != "") HASH[$2] = $8
    PERC[$8] = $2; DIM[$8] = $3; ID[$1] = $8
    if ($6 == "FATTA") fatte[$1] = 1
    if ($4 != "") { SIMILE[stelo($7) SUBSEP $4] = $8; SIMILE[stelo($8) SUBSEP $4] = $8 }
    if (giorno_di($8) != "") SIMILE[stelo($8) SUBSEP giorno_di($8)] = $8
    next
}
f == "lt" {
    p = substr($0, length($1) + 2)
    if (in_punto(p)) {
        if (p ~ /^\.compatibili\//) { n = nome_di(p); sub(/\.mp4$/, "", n); COMPATIBILI[n] = 1 }
        lt_punto++
        next
    }
    if (!e_media(p)) { lt_altri++; next }
    LTP[p] = $1; next
}
f == "cs"   { SCI[$2 SUBSEP $3 SUBSEP $4] = $1; next }
f == "cz" {
    k = $2 SUBSEP $3 SUBSEP $4
    if ($1 == "F") { ZFATTO[k] = $5; ZMAC[k] = $7; next }
    m = $0; for (i = 1; i <= 7; i++) sub(/^[^\t]*\t/, "", m)
    if (!((k SUBSEP m) in VISTO)) { VISTO[k SUBSEP m] = 1; nm[k]++; MS[k, nm[k]] = $5; MD[k, nm[k]] = $6; MG[k, nm[k]] = $7; MN[k, nm[k]] = m }
    next
}
f == "camp" { if ($1 == "uguale") camp_ok++; else camp_ko++; next }
f == "ass"  { ASS[$1 SUBSEP $2 SUBSEP $3 SUBSEP $4] = 1; next }
f == "ogg" {
    tipo = $1; classe = $2; dim = $3; data = $4; rel = $5; k = dim SUBSEP data SUBSEP rel
    fonte = radice "/" rel
    if (classe == "rimasti") fonte = radice "/" substr(rel, length(rimasti) + 2)
    if (classe == "escluso") {
        if (tipo == "zip") riga("pcloud", "escluso", "escluso-di-proposito", "", dim, radice "/" rel, "(zip non aperto)", "", "in ESCLUDI")
        else riga("pcloud", "escluso", "escluso-di-proposito", "", dim, radice "/" rel, "", "", "in ESCLUDI")
        if (tipo == "zip") zip_esclusi++; else media_esclusi++
        next
    }
    # Nell'elenco, ma pCloud dice che non c'è più (e non si è letto in un giro di prima).
    if ((tipo SUBSEP k) in ASS && !(tipo == "sciolto" ? (k in SCI) : (k in ZFATTO))) {
        spariti++
        riga("pcloud", "sparito", "non-piu-su-pcloud", "", dim, radice "/" rel, (tipo == "zip" ? "(zip non aperto)" : ""), "", "era nell'elenco di pCloud, ma pCloud dice che non c'è (directory not found): un fantasma, guardalo dal sito di pCloud")
        next
    }
    if (tipo == "sciolto") {
        sciolti++
        if (!(k in SCI)) { riga("pcloud", "mancante", "hash-non-letto", "", dim, radice "/" rel, "", "", "pCloud non l'ha dato e non si è scaricato: rilancia da 2"); next }
        UNICI[SCI[k]] = 1
        giudica(SCI[k], dim, data, rel, "", "", classe, fonte, 0)
        next
    }
    zip_tutti++
    if (!(k in ZFATTO)) { zip_non_fatti++; riga("pcloud", "mancante", "zip-non-confrontato", "", dim, radice "/" rel, "", "", "non ancora aperto: rilancia da 3"); next }
    mac += ZMAC[k]
    if (ZFATTO[k] > 1) zip_rovinati++
    if (nm[k] == 0) {
        motivo = (ZFATTO[k] > 1) ? "zip-rovinato" : "zip-senza-foto"
        riga("pcloud", (ZFATTO[k] > 1 ? "mancante" : "senza-foto"), motivo, "", dim, radice "/" rel, "", "", (ZFATTO[k] > 1 ? "unzip " ZFATTO[k] ", nessuna foto estratta" : "nessuna foto o video dentro"))
        next
    }
    for (i = 1; i <= nm[k]; i++) {
        membri++; UNICI[MS[k, i]] = 1
        giudica(MS[k, i], MD[k, i], data, rel, MN[k, i], MG[k, i], classe, fonte, ZFATTO[k])
    }
    next
}
END {
    # LifetimeCloud contro le schede.
    for (p in LTP) {
        if (p in PERC) { if (DIM[p] != "" && DIM[p] + 0 != LTP[p] + 0) riga("lifetime", "diverso", "dimensione-diversa", PERC[p], LTP[p], p, "", p, "nella scheda " DIM[p] " byte"); continue }
        if (p in IMPP) {
            sha = IMPP[p]
            if (sha in HASH) riga("lifetime", "non-indicizzato", "copia-identica", sha, LTP[p], p, "", HASH[sha], "la stessa foto ha già la scheda: l'app non ne fa una seconda")
            else riga("lifetime", "non-indicizzato", "da-indicizzare", sha, LTP[p], p, "", "", "da foto-da-pcloud.sh: Indicizza archivio")
        } else riga("lifetime", "non-indicizzato", "da-indicizzare", "", LTP[p], p, "", "", "non da foto-da-pcloud.sh: Indicizza archivio")
    }
    for (p in PERC) if (!(p in LTP)) riga("db", "senza-file", "senza-file", PERC[p], DIM[p], p, "", p, "la scheda c'è, il file su " dest " no")
    for (id in fatte) { nfatte++; if (!(id in COMPATIBILI)) fatte_senza++ }
    for (n in COMPATIBILI) { ncomp++; if (!(n in ID)) comp_orfane++ }
    close(rapporto)

    for (k in conta) {
        split(k, c, SUBSEP)
        if (c[1] == "pcloud") { per_esito[c[2]] += conta[k]; if (c[2] == "mancante" || c[2] == "probabile") { per_motivo[c[3]] += conta[k]; if (!(c[3] in SPIEGATO)) non_spiegati += conta[k] } }
        else if (c[1] == "lifetime") per_lt[c[3]] += conta[k]
        else senza_file += conta[k]
    }
    nu = 0; for (x in UNICI) nu++
    print "Confronto pCloud -> FotoTimeline (" strftime_no() ")"
    print ""
    printf "Su pCloud:         %d foto e video (%d sciolti, %d in %d zip), %d contenuti diversi\n", sciolti + membri, sciolti, membri, zip_tutti, nu
    printf "  esclusi apposta: %d foto e video e %d zip in ESCLUDI, non confrontati\n", media_esclusi, zip_esclusi
    if (mac > 0) printf "  copie ._ dei Mac negli zip (non sono foto, raccogli le butta): %d\n", mac
    if (zip_rovinati > 0) printf "  zip rovinati:    %d\n", zip_rovinati
    if (spariti > 0) printf "Non più su pCloud: %d (erano nell'elenco, ma pCloud dice che non ci sono: fantasmi, non contati sopra)\n", spariti
    if (per_esito["senza-foto"] > 0) printf "  zip senza foto né video dentro: %d\n", per_esito["senza-foto"]
    printf "Trovati:           %d (il loro SHA-256 è in una scheda di FotoTimeline)\n", per_esito["trovato"]
    printf "Probabili:         %d (stesso nome e data, contenuto diverso)\n", per_esito["probabile"]
    printf "Mancanti:          %d\n", per_esito["mancante"]
    print "Mancanti e probabili, per motivo:"
    fflush()
    for (m in per_motivo) printf "  %-26s %7d  %s\n", m, per_motivo[m], ((m in SPIEGATO) ? "" : "NON SPIEGATO") | "sort"
    close("sort")
    print ""
    printf "Su %s: schede %d", dest, nschede
    tot_lt = 0; for (p in LTP) tot_lt++
    printf ", foto e video %d (più %d file di altro tipo e %d nelle cartelle col punto)\n", tot_lt, lt_altri, lt_punto
    fflush()
    for (m in per_lt) printf "  %-26s %7d\n", m, per_lt[m] | "sort"
    close("sort")
    printf "  %-26s %7d%s\n", "schede senza file", senza_file, (senza_file > 0 ? "  NON SPIEGATO" : "")
    printf "  versioni compatibili dei video: %d (%d schede con conversione FATTA, %d senza il file; %d senza scheda)\n", ncomp, nfatte, fatte_senza, comp_orfane
    printf "  campione riscaricato: %d con lo SHA-256 della scheda, %d diversi%s\n", camp_ok, camp_ko, (camp_ko > 0 ? "  NON SPIEGATO" : "")
    print ""
    if (per_motivo["raccolto-non-indicizzato"] + per_lt["da-indicizzare"] > 0) print "Da fare: nell'app, Monta e Indicizza archivio (le foto raccolte e caricate senza scheda)."
    if (per_motivo["raccolto-non-caricato"] > 0) print "Da fare: foto-da-pcloud.sh carica (raccolte ma non su LifetimeCloud)."
    if (per_motivo["nuovo-dopo-scarica"] > 0) print "Da fare: arrivate su pCloud dopo lo scarica: un nuovo giro di foto-da-pcloud.sh (scarica, raccogli, carica)."
    nonsp = non_spiegati + senza_file + camp_ko
    if (nonsp > 0) { printf "RISULTATO: %d cose NON SPIEGATE (righe con quei motivi nel rapporto).\n", nonsp; exit 3 }
    if (spariti > 0) {
        printf "Attenzione: %d file erano nell'elenco di pCloud ma pCloud dice che non ci sono più (lui o la sua cartella).\n", spariti
        printf "  Quali:  awk -F'\\t' '$2 == \"sparito\"' %s | cut -f6\n", rapporto
        print "  Guardali dal sito di pCloud: se non ci sono neanche lì, sono fantasmi dell'elenco e non c'è niente da fare."
        printf "RISULTATO: tutto quello che c'è su pCloud è in FotoTimeline, o manca per un motivo noto (%d non più su pCloud, erano nell'elenco).\n", spariti
        exit
    }
    print "RISULTATO: tutto quello che c'è su pCloud è in FotoTimeline, o manca per un motivo noto."
}
function strftime_no(   d) { "date \"+%d/%m/%Y %H:%M\"" | getline d; close("date \"+%d/%m/%Y %H:%M\""); return d }
FINE
}

# --- giro in fila e in sottofondo, come verifica-tutto.sh -------------------------------
passo() {   # passo N: esegue il passo N
    local n="$1" nome="${NOMI[$(($1 - 1))]}"
    mkdir -p "$CONF"
    echo "[$n/$TOTALE] $nome: iniziato $(ora)" > "$PASSO"
    echo
    echo "===== [$n/$TOTALE] $nome ($(ora)) ====="
    case "$nome" in
        elenca) elenca ;;
        sciolti) sciolti ;;
        zip) zip_tutti ;;
        fototimeline) fototimeline ;;
        confronto) confronto ;;
    esac
}

esegui() {   # esegui DA: i passi da DA alla fine (lanciato da avvia, nel registro)
    local da="$1" n stato
    mkdir -p "$CONF"
    echo "$$" > "$PID"
    trap 'echo "[$n/$TOTALE] ${NOMI[$((n - 1))]}: FERMATO $(ora). Per riprendere: $0 da $n" > "$PASSO"; rm -f "$PID"; exit 143' TERM INT
    for ((n = da; n <= TOTALE; n++)); do
        stato=0
        # Ogni passo in un processo suo, con il suo set -e (dentro "|| stato=$?" non varrebbe):
        # un errore ferma lui, e qui si scrive da dove ripartire.
        "$SE" _passo "$n" || stato=$?
        if [ "$stato" -eq 3 ] && [ "$n" -eq "$TOTALE" ]; then
            echo "FATTO $(ora), ma con cose NON SPIEGATE: guarda $RIEPILOGO e $RAPPORTO" > "$PASSO"
            rm -f "$PID"
            exit 3
        elif [ "$stato" -ne 0 ]; then
            echo "[$n/$TOTALE] ${NOMI[$((n - 1))]}: ERRORE $(ora). Guarda $REGISTRO, poi: $0 da $n" > "$PASSO"
            echo
            echo "Fermo al passo $n (${NOMI[$((n - 1))]}). Sistemato il problema: $0 da $n"
            rm -f "$PID"
            exit 1
        fi
        echo "[$n/$TOTALE] ${NOMI[$((n - 1))]}: finito $(ora)" > "$PASSO"
    done
    local spariti
    spariti="$(sed -n 's/^Non più su pCloud: \([0-9]*\).*/\1/p' "$RIEPILOGO" 2>/dev/null | head -1)"
    [ -n "$spariti" ] && spariti=" ($spariti non più su pCloud, erano nell'elenco)"
    echo "FATTO $(ora): tutto trovato o mancante per un motivo noto$spariti. Riepilogo in $RIEPILOGO" > "$PASSO"
    rm -f "$PID"
}

avvia() {   # avvia DA [rifai]: il passo 1 in primo piano (la stima), il resto in sottofondo
    local da="$1" rifai="${2:-}" eta
    [[ "$da" =~ ^[0-9]+$ ]] && [ "$da" -ge 1 ] && [ "$da" -le "$TOTALE" ] || errore "passo da 1 a $TOTALE"
    controlla_impostazioni
    in_corso && errore "c'è già un giro in corso: $0 dove (o $0 ferma)"
    if [ "$da" -eq 1 ]; then
        eta=999999
        [ -f "$STIMA" ] && [ -f "$OGGETTI" ] && eta=$(( $(date +%s) - $(stat -c %Y "$OGGETTI") ))
        if [ -z "$rifai" ] && [ "$eta" -lt $((FRESCO_ORE * 3600)) ]; then
            echo "Uso l'elenco di pCloud di $((eta / 60)) minuti fa (per rifarlo: $0 stima)."
            stima
        else
            elenca
        fi
        da=2
    fi
    : > "$REGISTRO"
    # Subito "in partenza" e il pid: chi guarda (dove, un altro avvio) non vede il giro di prima.
    echo "[$da/$TOTALE] ${NOMI[$((da - 1))]}: in partenza $(ora)" > "$PASSO"
    setsid nohup "$SE" esegui "$da" >> "$REGISTRO" 2>&1 < /dev/null &
    echo "$!" > "$PID"
    echo "Partito dal passo $da di $TOTALE, in sottofondo."
    echo "Per vedere a che punto è:  $0 dove"
    echo "Per seguirlo:              tail -f $REGISTRO"
    echo "Per fermarlo:              $0 ferma   (poi $0 da N riprende)"
}

ferma() {
    if ! in_corso; then echo "Non c'è nessun giro in corso."; return 0; fi
    local p g
    p="$(cat "$PID")"
    g="$(ps -o pgid= -p "$p" | tr -d ' ')"
    # Il giro ha il suo gruppo (setsid): si fermano anche xargs, rclone e unzip.
    if [ "$g" = "$p" ]; then kill -TERM -- "-$p" 2>/dev/null || true; else kill -TERM "$p" 2>/dev/null || true; fi
    local i
    for ((i = 0; i < 300; i++)); do in_corso || break; sleep 0.1; done
    echo "Fermato. Quello già fatto resta: $0 dove dice da dove riprendere."
}

dove() {
    if [ ! -f "$PASSO" ]; then
        echo "Non è ancora partito: $0"
        return 0
    fi
    cat "$PASSO"
    if in_corso; then echo "(sta girando)"; fi
    if [ -f "$PIANO" ] && grep -Eq '^\[[23]/.*iniziato' "$PASSO"; then
        local cosa inizio n b fn fb trascorsi
        IFS=$'\t' read -r cosa inizio n b < "$PIANO"
        read -r fn fb < <(awk -F'\t' '{ n += $1; b += $2 } END { printf "%d %.0f\n", n, b }' "$FATTI" 2>/dev/null || echo "0 0")
        trascorsi=$(( $(date +%s) - inizio ))
        printf '%s: %s su %s fatti' "$cosa" "$fn" "$n"
        [ "$b" -gt 0 ] && printf ', scaricati %s su %s' "$(gb "$fb")" "$(gb "$b")"
        awk -v fn="$fn" -v n="$n" -v fb="$fb" -v b="$b" -v t="$trascorsi" 'BEGIN {
            if (t < 1 || (b > 0 ? fb : fn) <= 0) { print ""; exit }
            if (b > 0) { v = fb / t; r = (b - fb) / v; printf " (%.1f MB/s", v / 1e6 } else { r = (n - fn) / (fn / t); printf " (" }
            printf ", mancano circa %d h %02d min)\n", r / 3600, (r % 3600) / 60 }'
    fi
    [ -f "$STIMA" ] && sed -n '/^Scaricherà/p' "$STIMA"
    echo "--- ultime righe di $REGISTRO"
    tail -n 8 "$REGISTRO" 2>/dev/null || true
}

case "${1:-}" in
    "") avvia 1 ;;
    stima) controlla_impostazioni; in_corso && errore "c'è un giro in corso: $0 dove"; elenca ;;
    da) avvia "${2:-}" rifai ;;
    dove) dove ;;
    ferma) ferma ;;
    rapporto) confronto ;;
    esegui) esegui "${2:-2}" ;;
    _passo) passo "$2" ;;
    _zip) uno_zip "$2" ;;
    *) errore "uso: $0 [stima | dove | da N | ferma | rapporto]" ;;
esac
