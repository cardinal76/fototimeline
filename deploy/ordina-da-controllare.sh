#!/usr/bin/env bash
# Riordina quello che metti-da-parte di foto-da-pcloud.sh ha lasciato su pCloud in
# "Da controllare" (zip con dentro anche altri file, file non letti, zip rovinati), senza
# toccarlo: lo scarica un pezzo alla volta, lo apre e mette in "Da controllare ordinati":
#   Foto/AAAA/MM/GG/<nome>    le foto e i video nuovi (che FotoTimeline non ha), caricati
#                             anche in DESTINAZIONE (lifetime:FotoTimeline/AAAA/MM/GG)
#   Documenti/AAAA/MM/<nome>  gli altri file degli zip, col mese della loro data
#   Non letti/AAAA/MM/<nome>  le foto e i video che neanche stavolta si leggono
# Le foto già su FotoTimeline si contano soltanto. Gira sul PC (WSL), con rclone e i remote
# di foto-da-pcloud.sh (pcloud: e lifetime:); P: non serve.
#
#   ordina-da-controllare.sh anteprima  quanti zip e altri file ci sono in "Da controllare",
#                                       quanti ne restano da fare e quanti GB da scaricare
#   ordina-da-controllare.sh ordina     l'anteprima, poi chiede ORDINA e li fa tutti
#   ordina-da-controllare.sh carica     carica quello rimasto in attesa (lo fa già ordina)
#   ordina-da-controllare.sh stato      a che punto è, e il conto del registro
#
# Comandi per Marco (dalla cartella del repository):
#   ./deploy/ordina-da-controllare.sh anteprima
#   echo ORDINA | nohup ./deploy/ordina-da-controllare.sh ordina > ~/foto-da-controllare/ordina.log 2>&1 &
#   tail -f ~/foto-da-controllare/ordina.log
#   ./deploy/ordina-da-controllare.sh stato
# Poi nell'app: Monta, poi Indicizza archivio.
#
# Come lavora. Per ogni file di RIMASTI ("Da controllare"): rclone copyto in LAVORO/tmp, poi
#   - uno zip si apre con unzip; se unzip dice che è rovinato si prova a recuperarlo: 7z x
#     (se c'è e lo zip gli torna intero), poi zip -FF e, dallo zip riparato, unzip una voce
#     alla volta, poi unzip una voce alla volta dallo zip com'è. Si tiene solo una voce che
#     passa il controllo (CRC) del programma che la estrae: una foto a metà non si carica.
#     Le voci che non si recuperano (o tutto lo zip) vanno nel registro come "irrecuperabile";
#   - __MACOSX/, ._*, .DS_Store, Thumbs.db e desktop.ini si buttano; i collegamenti simbolici
#     dentro gli zip pure (non sono file);
#   - foto e video (le estensioni di raccogli) si ordinano come raccogli: EXIF, poi data del
#     video, poi data del file (lib-foto.sh, ordina_cartella). Vanno su FotoTimeline solo se
#     exiftool li riconosce come immagine o video, se no sono "non letti";
#   - gli altri file prendono anno e mese dalla loro data (quella dentro lo zip);
#   - un file con un'impronta (sha256) già vista, qui o in ALTRI_LAVORI, entra una volta sola;
#     due file diversi con lo stesso nome nella stessa cartella: nome.ext, nome-1.ext, ...
#     Un nome già usato su pCloud o su LifetimeCloud (anche da un'altra foto, per esempio
#     del telefono) non si riusa: si guarda prima la cartella di destinazione.
# I file nuovi aspettano in LAVORO/in-attesa e si caricano a lotti (ogni LOTTO zip o
# LOTTO_GB GB): prima le cartelle che mancano, una alla volta (come carica di
# foto-da-pcloud.sh, perché due MKCOL insieme su LifetimeCloud fanno cartelle doppie), poi
# rclone copy --ignore-existing e rclone check --one-way --size-only. Solo dopo il controllo
# entrano nel registro: un giro interrotto, rilanciato, carica prima quello in attesa e poi
# rifà solo i file non ancora fatti.
#
# Non tocca mai gli originali: in RIMASTI si legge soltanto (niente delete, move, purge, sync
# né rm sui remote), su pCloud si scrive solo dentro ORDINATI e su LifetimeCloud si
# aggiungono file nuovi in DESTINAZIONE, sempre senza sovrascrivere. Gli zip in "Da
# controllare" li butta Marco a mano, quando ha guardato.
#
# I file di LAVORO (predefinito ~/foto-da-controllare):
#   registro.tsv  origine \t file nello zip (vuoto per un file sciolto) \t sha256 \t dove è
#                 finito \t esito. Esiti: nuova su FotoTimeline (dove: in ORDINATI/Foto, e lo
#                 stesso AAAA/MM/GG/nome in DESTINAZIONE), già su FotoTimeline (dove: in
#                 DESTINAZIONE), documento, non letto, doppione (dove: la prima copia),
#                 irrecuperabile
#   impronte.tsv  sha256 \t AAAA/MM/GG/nome in DESTINAZIONE: le foto nuove portate da qui,
#                 nello stesso formato di foto-da-pcloud.sh (impronte.tsv di ~/foto-da-pcloud
#                 si legge soltanto: lì restano le foto di raccogli)
#   fatti.tsv     percorso in RIMASTI \t ordinato | irrecuperabile
#   elenco.tsv    i file di RIMASTI con il peso, dall'ultima anteprima
#
# Impostazioni (davanti al comando; le prime vengono da ~/foto-da-pcloud/impostazioni, poi
# si ricordano in LAVORO/impostazioni):
#   LAVORO_PCLOUD il lavoro di foto-da-pcloud.sh (predefinito ~/foto-da-pcloud)
#   RADICE PCLOUD DESTINAZIONE RIMASTI  come in foto-da-pcloud.sh
#   ORDINATI      dove mettere i file su pCloud (predefinita "RADICE/Da controllare ordinati")
#   ALTRI_LAVORI  i LAVORO le cui foto sono già su FotoTimeline (separati da ":"), predefiniti
#                 ~/foto-da-pcloud e ~/foto-da-amazon (se ci sono). Si leggono soltanto
#   PARALLELI     quanti file si scaricano e si aprono insieme (predefinito 4). Sul PC restano
#                 al massimo PARALLELI+1 file aperti, più il lotto in attesa
#   LOTTO, LOTTO_GB  ogni quanti zip, o GB in attesa, si carica (predefiniti 50 e 2)
#   CARICA_PARALLELI quanti file si mandano insieme (predefinito 16)
#
# Serve: unzip, zip, exiftool, rclone (sudo apt install unzip zip libimage-exiftool-perl rclone);
# 7z facoltativo (sudo apt install p7zip-full), per qualche zip rovinato in più.
set -euo pipefail

FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/foto-da-controllare}"
LAVORO_PCLOUD="${LAVORO_PCLOUD:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato, e quello ricordato qui più
# di quello di foto-da-pcloud.sh.
DATI_RADICE="${RADICE:-}" DATI_PCLOUD="${PCLOUD:-}" DATI_DESTINAZIONE="${DESTINAZIONE:-}"
DATI_RIMASTI="${RIMASTI:-}" DATI_ORDINATI="${ORDINATI:-}" DATI_ALTRI="${ALTRI_LAVORI-__nessuno__}"
RADICE="" PCLOUD="" DESTINAZIONE="" RIMASTI="" ORDINATI="" ALTRI_LAVORI="__nessuno__"
if [ -f "$LAVORO_PCLOUD/impostazioni" ]; then
    # shellcheck disable=SC1091
    . "$LAVORO_PCLOUD/impostazioni"
fi
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
RADICE="${DATI_RADICE:-$RADICE}"
PCLOUD="${DATI_PCLOUD:-$PCLOUD}"
DESTINAZIONE="${DATI_DESTINAZIONE:-${DESTINAZIONE:-lifetime:FotoTimeline}}"
RIMASTI="${DATI_RIMASTI:-$RIMASTI}"
ORDINATI="${DATI_ORDINATI:-$ORDINATI}"
[ "$DATI_ALTRI" != __nessuno__ ] && ALTRI_LAVORI="$DATI_ALTRI"
[ "$ALTRI_LAVORI" = __nessuno__ ] && ALTRI_LAVORI="$LAVORO_PCLOUD:$HOME/foto-da-amazon"
RCLONE="${RCLONE:-rclone}"
LOTTO="${LOTTO:-50}"
LOTTO_GB="${LOTTO_GB:-2}"
NOME_MAX="${NOME_MAX:-255}"
export TZ="${TZ:-Europe/Rome}"

REGISTRO="$LAVORO/registro.tsv"   # origine \t file nello zip \t sha256 \t dove \t esito
IMPRONTE="$LAVORO/impronte.tsv"   # sha256 \t AAAA/MM/GG/nome in DESTINAZIONE (le foto nuove)
FATTI="$LAVORO/fatti.tsv"         # percorso in RIMASTI \t ordinato | irrecuperabile
ELENCO="$LAVORO/elenco.tsv"       # peso \t percorso in RIMASTI
ATTESA="$LAVORO/in-attesa"        # file/ e le righe di registro, impronte e fatti da caricare
TMP="$LAVORO/tmp"
# Per lib-foto.sh: qui si usano solo ordina_cartella, impronte, in_fila e crea_cartelle_mancanti.
# shellcheck disable=SC2034
ORDINATE="$ATTESA/file" MANIFEST=/dev/null FONTI=/dev/null DA_CONTROLLARE=/dev/null
ARCHIVIATI=/dev/null SPECCHIO="" ARCHIVIO=""
DOVE_ORIGINE="su pCloud" DOVE_ARCHIVIO="su pCloud" DATA_DAL_NOME="" ZIP_SUL_POSTO=""
COPIE_SCARICATE="" FASE_IN_FILA=ordina
SCARTA_NEI_ZIP=()
# shellcheck source=lib-foto.sh
. "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/lib-foto.sh"

# La spazzatura di sistema dentro gli zip: non si tiene e non si registra.
SPAZZATURA=(.DS_Store Thumbs.db desktop.ini '._*')
# Quanto può durare zip -FF: se chiedesse qualcosa d'altro, senza risposta ripeterebbe la
# domanda per sempre.
TEMPO_FF="${TEMPO_FF:-900}"

salva_impostazioni() {
    printf 'RADICE=%q\nPCLOUD=%q\nDESTINAZIONE=%q\nRIMASTI=%q\nORDINATI=%q\nALTRI_LAVORI=%q\n' \
        "$RADICE" "$PCLOUD" "$DESTINAZIONE" "$RIMASTI" "$ORDINATI" "$ALTRI_LAVORI" > "$IMPOSTAZIONI"
}

# RIMASTI e ORDINATI: due cartelle diverse dentro RADICE, nessuna dentro l'altra.
controlla_impostazioni() {
    [ -n "$RADICE" ] || errore "manca RADICE: non trovo $LAVORO_PCLOUD/impostazioni (LAVORO_PCLOUD=... per un altro) e non l'hai data"
    [ -n "$PCLOUD" ] || errore "manca PCLOUD, il remote rclone di RADICE: per esempio PCLOUD=pcloud: $0 $FASE"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    RIMASTI="${RIMASTI:-Da controllare}"
    ORDINATI="${ORDINATI:-Da controllare ordinati}"
    case "$RIMASTI" in /*) ;; *) RIMASTI="$RADICE/$RIMASTI" ;; esac
    case "$ORDINATI" in /*) ;; *) ORDINATI="$RADICE/$ORDINATI" ;; esac
    RIMASTI="${RIMASTI%/}" ORDINATI="${ORDINATI%/}"
    case "$RIMASTI/" in "$RADICE"/?*/) ;; *) errore "RIMASTI ($RIMASTI) dev'essere una cartella dentro RADICE ($RADICE)" ;; esac
    case "$ORDINATI/" in "$RADICE"/?*/) ;; *) errore "ORDINATI ($ORDINATI) dev'essere una cartella dentro RADICE ($RADICE)" ;; esac
    case "$ORDINATI/" in "$RIMASTI"/*) errore "ORDINATI ($ORDINATI) non può essere RIMASTI o stare dentro RIMASTI" ;; esac
    case "$RIMASTI/" in "$ORDINATI"/*) errore "RIMASTI ($RIMASTI) non può stare dentro ORDINATI ($ORDINATI)" ;; esac
    [[ "$PARALLELI" =~ ^[1-9][0-9]*$ ]] || errore "PARALLELI dev'essere un numero da 1 in su (è '$PARALLELI')"
    [[ "$LOTTO" =~ ^[1-9][0-9]*$ ]] || errore "LOTTO dev'essere un numero da 1 in su (è '$LOTTO')"
    [[ "$LOTTO_GB" =~ ^[0-9]+$ ]] || errore "LOTTO_GB dev'essere un numero di GB (è '$LOTTO_GB')"
    return 0
}

# Il percorso rclone di un percorso sotto RADICE (/mnt/p/Da controllare/x.zip ->
# pcloud:Da controllare/x.zip), come in foto-da-pcloud.sh.
remoto_di() {
    local rel="${1#"$RADICE"/}"
    case "$PCLOUD" in *:) printf '%s%s' "$PCLOUD" "$rel" ;; *) printf '%s/%s' "$PCLOUD" "$rel" ;; esac
}
# Una cartella dentro un remote: "lifetime:FotoTimeline" + "2019/01/01".
sotto() {
    case "$1" in *:) printf '%s%s' "$1" "$2" ;; *) printf '%s/%s' "$1" "$2" ;; esac
}

byte() { printf '%s' "$1" | LC_ALL=C wc -c; }
# nome_numero NOME N: NOME con "-N" prima dell'estensione (N=0: com'è), entro NOME_MAX byte
# (il disco di Linux non ne vuole di più): si accorcia la parte prima dell'estensione,
# senza spezzare le lettere accentate.
nome_numero() {
    local nome="$1" n="$2" base="$1" est="" suf="" max
    case "$nome" in ?*.*) base="${nome%.*}"; est=".${nome##*.}" ;; esac
    if [ "$n" -gt 0 ]; then suf="-$n"; fi
    max=$((NOME_MAX - $(byte "$est$suf")))
    if [ "$max" -lt 16 ]; then base="$nome" est="" max=$((NOME_MAX - $(byte "$suf"))); fi
    if [ "$(byte "$base")" -gt "$max" ]; then
        base="$(printf '%s' "$base" | head -c "$max" | iconv -f UTF-8 -t UTF-8 -c || true)"
    fi
    printf '%s%s%s' "$base" "$suf" "$est"
}
# Tab e a capo nei nomi non entrano nel registro (è un tsv): diventano spazi.
pulito() { local s="${1//$'\t'/ }"; printf '%s' "${s//$'\n'/ }"; }

e_media() {
    local e="${1##*.}"
    [[ "$1" == *.* ]] || return 1
    [[ " ${ESTENSIONI[*]} " == *" ${e,,} "* ]]
}

# --- Elenco di RIMASTI e anteprima ---------------------------------------------------------
declare -A FATTO=() PESO=()
declare -a VOCI=()
carica_fatti() {
    local rel e f
    FATTO=()
    for f in "$FATTI" "$ATTESA/fatti.tsv"; do
        [ -f "$f" ] || continue
        while IFS=$'\t' read -r rel e; do if [ -n "$rel" ]; then FATTO[$rel]="$e"; fi; done < "$f"
    done
    return 0
}

# Legge da pCloud cosa c'è in RIMASTI (un solo lsf) e mette in VOCI quello che manca.
elenca() {
    local uscita peso rel st=0
    echo "Leggo cosa c'è in $RIMASTI ($(remoto_di "$RIMASTI"))…"
    uscita="$(rc lsf -R --files-only --format sp --separator $'\t' "$(remoto_di "$RIMASTI")")" || st=$?
    if [ "$st" -eq 3 ]; then errore "$RIMASTI su pCloud non c'è: prima foto-da-pcloud.sh metti-da-parte"; fi
    [ "$st" -eq 0 ] || errore "non riesco a leggere $(remoto_di "$RIMASTI"): rilancia $0 $FASE"
    printf '%s\n' "$uscita" | sed '/^$/d' | LC_ALL=C sort -t $'\t' -k2 > "$ELENCO"
    carica_fatti
    VOCI=() PESO=()
    N_TUTTI=0 N_ZIP=0 N_ALTRI=0 N_FATTI=0 PESO_DA_FARE=0 PESO_MAX=0
    while IFS=$'\t' read -r peso rel; do
        [ -n "$rel" ] || continue
        N_TUTTI=$((N_TUTTI + 1))
        if [ -n "${FATTO[$rel]:-}" ]; then N_FATTI=$((N_FATTI + 1)); continue; fi
        VOCI+=("$rel"); PESO[$rel]="$peso"
        case "${rel,,}" in *.zip) N_ZIP=$((N_ZIP + 1)) ;; *) N_ALTRI=$((N_ALTRI + 1)) ;; esac
        PESO_DA_FARE=$((PESO_DA_FARE + peso))
        if [ "$peso" -gt "$PESO_MAX" ]; then PESO_MAX="$peso"; fi
    done < "$ELENCO"
    return 0
}

gb() { numfmt --to=iec --suffix=B "$1"; }

mostra_anteprima() {
    echo "In $RIMASTI: $N_TUTTI file, già fatti $N_FATTI."
    if [ -d "$ATTESA" ]; then
        echo "In attesa di carica da un giro interrotto: $(wc -l 2>/dev/null < "$ATTESA/fatti.tsv" || echo 0) file ($(du -sh "$ATTESA" | cut -f1)): si caricano per primi."
    fi
    if [ "${#VOCI[@]}" -eq 0 ]; then
        echo "Da fare: niente."
        return 0
    fi
    echo "Da fare: $N_ZIP zip e $N_ALTRI altri file (foto e video non letti, ...), $(gb "$PESO_DA_FARE") da scaricare."
    echo "Si scaricano $PARALLELI alla volta in $TMP e si cancellano appena aperti: sul PC servono"
    echo "circa $(gb $(( PESO_MAX * 3 * (PARALLELI + 1) ))) (il più grande è $(gb "$PESO_MAX"), aperto pesa di più) più il lotto in attesa (${LOTTO_GB} GB)."
    echo "Per esempio:"
    printf '    %s\n' "${VOCI[@]:0:10}"
    echo "Vanno in $ORDINATI (Foto, Documenti, Non letti); le foto nuove anche in $DESTINAZIONE."
    echo "In $RIMASTI non si tocca niente."
}

anteprima() {
    richiede "$RCLONE"
    controlla_impostazioni
    salva_impostazioni
    elenca
    mostra_anteprima
    if [ "${#VOCI[@]}" -gt 0 ]; then
        echo
        echo "Per cominciare:  $0 ordina   (chiede ORDINA)"
    fi
}

# --- Le impronte già note: FotoTimeline (altri lavori e questo), i nomi già usati ----------
declare -A MIA=() USATO=() REMOTO_P=() REMOTO_D=() ELENCATA=()
carica_altri_lavori() {
    local x
    local IFS=:
    for x in $ALTRI_LAVORI; do
        [ -n "$x" ] || continue
        x="${x%/}"
        [ "$x" = "${LAVORO%/}" ] && continue
        if [ -f "$x/impronte.tsv" ]; then
            carica_impronte_di "$x/impronte.tsv"
            echo "Già su FotoTimeline da $x: $(wc -l < "$x/impronte.tsv") foto e video"
        fi
    done
    unset IFS
    return 0
}
# Dal registro (e da quello in attesa): le impronte già messe da qui e i nomi usati in ORDINATI.
carica_miei() {
    local f o m sha dove esito
    MIA=() USATO=()
    for f in "$REGISTRO" "$ATTESA/registro.tsv"; do
        [ -f "$f" ] || continue
        while IFS=$'\t' read -r o m sha dove esito; do
            case "$esito" in
                "nuova su FotoTimeline"|documento|"non letto")
                    MIA[$sha]="$dove"
                    USATO[${dove#"$ORDINATI"/}]=1 ;;
            esac
        done < "$f"
    done
    return 0
}

# Cosa c'è già in ORDINATI su pCloud (un lsf all'inizio): nome -> peso.
elenca_ordinati() {
    local uscita peso rel st=0
    REMOTO_P=()
    uscita="$(rc lsf -R --files-only --format sp --separator $'\t' "$(remoto_di "$ORDINATI")" 2>/dev/null)" || st=$?
    [ "$st" -eq 0 ] || [ "$st" -eq 3 ] || errore "non riesco a leggere $(remoto_di "$ORDINATI"): rilancia $0 $FASE"
    while IFS=$'\t' read -r peso rel; do
        if [ -n "$rel" ]; then REMOTO_P[$rel]="$peso"; fi
    done <<< "$uscita"
    return 0
}
# Cosa c'è in una cartella AAAA/MM/GG di DESTINAZIONE, letta la prima volta che serve: anche
# le foto arrivate da altrove (telefono) hanno un nome, e non si sovrascrivono.
elenca_destinazione() {
    local dir="$1" uscita peso nome st=0
    [ -n "${ELENCATA[$dir]:-}" ] && return 0
    uscita="$(rc lsf --files-only --format sp --separator $'\t' "$(sotto "$DESTINAZIONE" "$dir")" 2>/dev/null)" || st=$?
    [ "$st" -eq 0 ] || [ "$st" -eq 3 ] || errore "non riesco a leggere $(sotto "$DESTINAZIONE" "$dir"): rilancia $0 $FASE"
    while IFS=$'\t' read -r peso nome; do
        if [ -n "$nome" ]; then REMOTO_D[$dir/$nome]="$peso"; fi
    done <<< "$uscita"
    ELENCATA[$dir]=1
}

# occupato PERCORSO PESO [foto]: PERCORSO (relativo a ORDINATI) è già di un altro file? Un
# file con lo stesso nome e lo stesso peso su pCloud o LifetimeCloud, che nessun registro
# conosce, è questo stesso file caricato da un giro interrotto prima del registro: si riusa.
occupato() {
    local p="$1" peso="$2" foto="${3:-}" d
    [ -n "${USATO[$p]:-}" ] && return 0
    if [ -n "$foto" ]; then
        d="${p#Foto/}"
        [ -n "${PRESO[$d]:-}" ] && return 0
        if [ -n "${REMOTO_D[$d]+c}" ] && [ "${REMOTO_D[$d]}" != "$peso" ]; then return 0; fi
    fi
    if [ -n "${REMOTO_P[$p]+c}" ] && [ "${REMOTO_P[$p]}" != "$peso" ]; then return 0; fi
    return 1
}
# libero CARTELLA NOME PESO [foto]: in LIBERO il primo CARTELLA/nome libero (nome, nome-1, ...).
libero() {
    local cartella="$1" nome="$2" peso="$3" foto="${4:-}" n=0 prova
    if [ -n "$foto" ]; then elenca_destinazione "${cartella#Foto/}"; fi
    prova="$(nome_numero "$nome" 0)"
    while occupato "$cartella/$prova" "$peso" "$foto"; do
        n=$((n + 1))
        prova="$(nome_numero "$nome" "$n")"
    done
    LIBERO="$cartella/$prova"
}

# --- Aprire uno zip, anche rovinato -----------------------------------------------------
# modello VOCE: la voce come modello di unzip, con [ * ? presi alla lettera.
modello() { printf '%s' "$1" | sed 's/[[*?]/[&]/g'; }
# unisci DA A: i file estratti in DA (una prova) dentro A, senza sovrascrivere; poi via DA.
unisci() {
    local da="$1" a="$2" f r
    while IFS= read -r -d '' f; do
        r="${f#"$da"/}"
        mkdir -p "$(dirname "$a/$r")"
        mv -n -- "$f" "$a/$r"
    done < <(find "$da" -type f -print0)
    rm -rf "$da"
}
# troppo_lungo VOCE: una parte del percorso supera NOME_MAX byte (il disco non la crea).
troppo_lungo() {
    local parte
    local IFS=/
    for parte in $1; do
        if [ "$(byte "$parte")" -gt "$NOME_MAX" ]; then return 0; fi
    done
    return 1
}
# una_alla_volta ZIP: le voci di ZIP non ancora recuperate, una alla volta con unzip, tenute
# solo se unzip (che controlla il CRC) finisce bene. Una voce col nome troppo lungo si
# controlla, si estrae con unzip -p col nome accorciato (senza cartelle) e prende la sua
# data dall'indice. Vede d, VISTA, RECUPERATA.
una_alla_volta() {
    local z="$1" v st elenco m corto quando
    elenco="$(unzip -Z1 "$z" 2>/dev/null </dev/null)" || return 0
    while IFS= read -r v; do
        [ -n "$v" ] || continue
        case "$v" in */) continue ;; esac
        VISTA[$v]=1
        [ -n "${RECUPERATA[$v]:-}" ] && continue
        rm -rf "$d/prova"
        m="$(modello "$v")"
        st=0
        if troppo_lungo "$v"; then
            corto="$(nome_numero "${v##*/}" 0)"
            unzip -tqq -P nessuna "$z" "$m" </dev/null >/dev/null 2>&1 || st=$?
            if [ "$st" -le 1 ]; then
                mkdir -p "$d/prova"
                unzip -p -P nessuna "$z" "$m" </dev/null > "$d/prova/$corto" 2>/dev/null || st=$?
                quando="$(unzip -Z -T "$z" "$m" 2>/dev/null </dev/null | awk 'NR == 1 { print $7 }' || true)"
                if [[ "$quando" =~ ^([0-9]{4})([0-9]{2})([0-9]{2})\.([0-9]{2})([0-9]{2})([0-9]{2})$ ]]; then
                    touch -d "${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]} ${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]}" "$d/prova/$corto" || true
                fi
            fi
        else
            unzip -qq -o -P nessuna "$z" "$m" -d "$d/prova" </dev/null >/dev/null 2>&1 || st=$?
        fi
        if [ "$st" -le 1 ] && [ -d "$d/prova" ]; then
            unisci "$d/prova" "$d/in"
            RECUPERATA[$v]=1
        fi
        rm -rf "$d/prova"
    done <<< "$elenco"
    return 0
}
# estrai ZIP: il contenuto in $d/in. In $d/modo: intero, recuperato o irrecuperabile; in
# $d/persi (separate da NUL) le voci che non si sono recuperate.
estrai() {
    local z="$1" st=0 v
    : > "$d/persi"
    unzip -qq -o -P nessuna "$z" -d "$d/in" </dev/null > "$d/unzip.txt" 2>&1 || st=$?
    if [ "$st" -le 1 ]; then
        echo intero > "$d/modo"
        return 0
    fi
    echo "    unzip non lo apre tutto (unzip $st): provo a recuperarlo"
    rm -rf "$d/in"; mkdir -p "$d/in"
    # 1. 7z, se c'è: se lo zip gli torna intero (unzip a volte rifiuta zip buoni), tutto.
    if command -v 7z >/dev/null && 7z t -y -pnessuna "$z" </dev/null >/dev/null 2>&1; then
        if 7z x -y -pnessuna -o"$d/prova" "$z" </dev/null >/dev/null 2>&1; then
            unisci "$d/prova" "$d/in"
            echo "    recuperato tutto con 7z"
            echo recuperato > "$d/modo"
            return 0
        fi
        rm -rf "$d/prova" "$d/in"; mkdir -p "$d/in"
    fi
    local -A VISTA=() RECUPERATA=()
    # 2. unzip una voce alla volta dallo zip com'è, se l'indice si legge (nomi troppo lunghi,
    #    una voce rovinata in mezzo a quelle buone).
    una_alla_volta "$z"
    # 3. zip -FF rifà l'indice dalle voci che trova; poi unzip, una voce alla volta, per
    #    quelle che mancano ancora (o tutte, se l'indice non c'era).
    if command -v zip >/dev/null && { [ "${#VISTA[@]}" -eq 0 ] || [ "${#RECUPERATA[@]}" -lt "${#VISTA[@]}" ]; }; then
        timeout "$TEMPO_FF" zip -FF "$z" --out "$d/riparato.zip" <<< y >/dev/null 2>&1 || true
        if [ -f "$d/riparato.zip" ]; then una_alla_volta "$d/riparato.zip"; fi
        rm -f "$d/riparato.zip"
    fi
    if [ "${#VISTA[@]}" -eq 0 ]; then
        echo "    irrecuperabile: non si legge nessuna voce"
        echo irrecuperabile > "$d/modo"
        return 0
    fi
    for v in "${!VISTA[@]}"; do
        if [ -z "${RECUPERATA[$v]:-}" ]; then printf '%s\0' "$v"; fi
    done | LC_ALL=C sort -z > "$d/persi"
    echo "    recuperati ${#RECUPERATA[@]} file su ${#VISTA[@]}"
    echo recuperato > "$d/modo"
}

# --- I lavori di in_fila: un file di RIMASTI ciascuno -----------------------------------
titolo_voce() {
    echo "[$(($1 + 1))/${#VOCI[@]}] ${VOCI[$1]} ($(gb "${PESO[${VOCI[$1]}]}"))"
}

# La parte lenta, in parallelo: scarica, apre, butta la spazzatura, impronte e date.
# Scrive solo nella sua cartella d. In d/voci, per ogni file (separati da NUL):
#   tipo (media, doc, nonletto)  percorso nello zip  sha256  file qui  proposta
# dove proposta è AAAA/MM/GG/nome per i media, AAAA/MM/nome per gli altri.
prepara_voce() {
    local rel="${VOCI[$1]}" d="$2" locale nome
    mkdir -p "$d/scaricato" "$d/in" "$d/ord"
    case "${rel,,}" in
        *.zip) locale="$d/scaricato/a.zip" ;;
        *) nome="$(nome_numero "${rel##*/}" 0)"; locale="$d/scaricato/$nome" ;;
    esac
    if ! rc copyto "$(remoto_di "$RIMASTI/$rel")" "$locale" </dev/null; then
        : > "$d/non-scaricato"
        return 0
    fi
    case "${rel,,}" in
        *.zip)
            estrai "$locale"
            rm -f "$locale"
            if [ "$(cat "$d/modo")" = irrecuperabile ]; then return 0; fi ;;
        *)
            echo sciolto > "$d/modo"
            : > "$d/persi"
            mkdir -p "$d/in/sciolto"
            mv -- "$locale" "$d/in/sciolto/$nome" ;;
    esac
    # Spazzatura e cose che non sono file (collegamenti, ...).
    find "$d/in" -depth -type d -name __MACOSX -exec rm -rf {} +
    local s
    for s in "${SPAZZATURA[@]}"; do find "$d/in" -iname "$s" -type f -delete; done
    find "$d/in" ! -type f ! -type d -delete

    local -a membri=()
    local -A GIORNO=()
    local riga f
    while IFS= read -r -d '' riga; do
        f="${riga%$'\t'*}"
        membri+=("$f"); GIORNO[$f]="${riga##*$'\t'}"
    done < <(find "$d/in" -type f -printf '%p\t%TY/%Tm/%Td\0' | LC_ALL=C sort -z)
    : > "$d/voci"
    [ "${#membri[@]}" -gt 0 ] || return 0
    impronte < <(printf '%s\0' "${membri[@]}")
    local -A SHA_DI=()
    for f in "${membri[@]}"; do SHA_DI[$f]="${SHA[$f]}"; done
    ordina_cartella "$d/in" "$d/ord"
    # Dove sono finiti i media (per impronta), e cosa ne dice exiftool (MIMEType).
    local -A IN_ORD=() MIME=()
    local sha m p
    while IFS= read -r -d '' riga; do
        sha="${riga%%  *}" p="${riga#*  }"
        [ -n "${IN_ORD[$sha]:-}" ] || IN_ORD[$sha]="$p"
    done < <(impronte_di_cartella "$d/ord")
    # shellcheck disable=SC2016
    while IFS=$'\t' read -r m p; do
        if [ -n "$p" ]; then MIME[$p]="$m"; fi
    done < <(exiftool -q -q -r -f "${estensioni_exif[@]}" -p '${MIMEType}	${Directory}/${FileName}' "$d/ord" "$d/in" 2>/dev/null </dev/null || true)

    local tipo dentro proposta giorno qui sciolto=""
    if [ "$(cat "$d/modo")" = sciolto ]; then sciolto=si; fi
    for f in "${membri[@]}"; do
        sha="${SHA_DI[$f]}" giorno="${GIORNO[$f]}" nome="${f##*/}"
        dentro="${f#"$d/in"/}"
        if [ -n "$sciolto" ]; then dentro=""; fi
        if e_media "$f"; then
            qui="${IN_ORD[$sha]:-$f}"
            case "${MIME[$qui]:-}" in
                image/*|video/*)
                    tipo=media
                    if [ "$qui" != "$f" ]; then proposta="${qui#"$d/ord"/}"; else proposta="$giorno/$nome"; fi ;;
                *)
                    tipo=nonletto proposta="${giorno%/*}/$nome" ;;
            esac
        else
            tipo=doc qui="$f" proposta="${giorno%/*}/$nome"
        fi
        proposta="${proposta//$'\t'/_}"; proposta="${proposta//$'\n'/_}"
        printf '%s\0%s\0%s\0%s\0%s\0' "$tipo" "$dentro" "$sha" "$qui" "$proposta" >> "$d/voci"
    done
}

# Il resto, in fila e nell'ordine: doppioni, nomi, i file nel lotto in attesa, le righe.
declare -A CONTO=()
applica_voce() {
    local rel="${VOCI[$1]}" d="$2" origine modo
    origine="$RIMASTI/$rel"
    if [ -f "$d/non-scaricato" ]; then
        echo "    non scaricato: si riprova al prossimo giro"
        NON_SCARICATI=$((NON_SCARICATI + 1))
        return 0
    fi
    modo="$(cat "$d/modo")"
    mkdir -p "$ATTESA/file"
    local righe="$d/righe.tsv" nuove="$d/impronte.tsv"
    : > "$righe"; : > "$nuove"
    if [ "$modo" = irrecuperabile ]; then
        printf '%s\t\t\t\t%s\n' "$(pulito "$origine")" irrecuperabile >> "$righe"
        CONTO[irrecuperabile]=$(( ${CONTO[irrecuperabile]:-0} + 1 ))
        chiudi_voce "$rel" irrecuperabile "$righe" "$nuove"
        return 0
    fi
    local tipo dentro sha qui proposta peso esito dove cartella n=0
    while IFS= read -r -d '' tipo && IFS= read -r -d '' dentro && IFS= read -r -d '' sha \
        && IFS= read -r -d '' qui && IFS= read -r -d '' proposta; do
        n=$((n + 1))
        if [ "$tipo" != doc ] && [ -n "${IMPRONTA[$sha]:-}" ]; then
            esito="già su FotoTimeline" dove="$(sotto "$DESTINAZIONE" "${IMPRONTA[$sha]}")"
        elif [ -n "${MIA[$sha]:-}" ]; then
            esito=doppione dove="${MIA[$sha]}"
        else
            peso="$(stat -c %s -- "$qui")"
            case "$tipo" in
                media) cartella="Foto/${proposta%/*}"; esito="nuova su FotoTimeline"
                       libero "$cartella" "${proposta##*/}" "$peso" foto ;;
                doc) cartella="Documenti/${proposta%/*}"; esito=documento
                     libero "$cartella" "${proposta##*/}" "$peso" ;;
                *) cartella="Non letti/${proposta%/*}"; esito="non letto"
                   libero "$cartella" "${proposta##*/}" "$peso" ;;
            esac
            mkdir -p "$ATTESA/file/$cartella"
            mv -- "$qui" "$ATTESA/file/$LIBERO"
            IN_ATTESA_BYTE=$((IN_ATTESA_BYTE + peso))
            dove="$ORDINATI/$LIBERO"
            USATO[$LIBERO]=1
            MIA[$sha]="$dove"
            if [ "$tipo" = media ]; then printf '%s\t%s\n' "$sha" "${LIBERO#Foto/}" >> "$nuove"; fi
        fi
        CONTO[$esito]=$(( ${CONTO[$esito]:-0} + 1 ))
        printf '%s\t%s\t%s\t%s\t%s\n' "$(pulito "$origine")" "$(pulito "$dentro")" "$sha" "$(pulito "$dove")" "$esito" >> "$righe"
    done < "$d/voci"
    local persi=0
    while IFS= read -r -d '' dentro; do
        persi=$((persi + 1))
        printf '%s\t%s\t\t\t%s\n' "$(pulito "$origine")" "$(pulito "$dentro")" irrecuperabile >> "$righe"
    done < "$d/persi"
    CONTO[irrecuperabile]=$(( ${CONTO[irrecuperabile]:-0} + persi ))
    if [ "$modo" != sciolto ]; then
        echo "    $n file$([ "$persi" -gt 0 ] && echo ", $persi irrecuperabili")"
    fi
    chiudi_voce "$rel" ordinato "$righe" "$nuove"
}
# Le righe di un file nel lotto in attesa; se il lotto è pieno, si carica.
chiudi_voce() {
    local rel="$1" come="$2" righe="$3" nuove="$4"
    cat "$righe" >> "$ATTESA/registro.tsv"
    cat "$nuove" >> "$ATTESA/impronte.tsv"
    printf '%s\t%s\n' "$rel" "$come" >> "$ATTESA/fatti.tsv"
    IN_ATTESA_VOCI=$((IN_ATTESA_VOCI + 1))
    if [ "$IN_ATTESA_VOCI" -ge "$LOTTO" ] || [ "$IN_ATTESA_BYTE" -ge $((LOTTO_GB * 1024 * 1024 * 1024)) ]; then
        carica_attesa
    fi
    return 0
}

# --- Caricare il lotto in attesa -----------------------------------------------------------
# Le cartelle prima (una alla volta, crea_cartelle_mancanti di lib-foto.sh), poi la copia
# senza sovrascrivere, poi il controllo; solo allora le righe entrano nel registro.
carica_attesa() {
    IN_ATTESA_VOCI=0 IN_ATTESA_BYTE=0
    [ -d "$ATTESA" ] || return 0
    local file="$ATTESA/file" pord paralleli="${CARICA_PARALLELI:-16}"
    pord="$(remoto_di "$ORDINATI")"
    if [ -d "$file" ] && [ -n "$(find "$file" -type f -print -quit)" ]; then
        echo "Carico il lotto in attesa: $(find "$file" -type f | wc -l) file ($(du -sh "$file" | cut -f1))…"
        if [ -d "$file/Foto" ] && [ -n "$(find "$file/Foto" -type f -print -quit)" ]; then
            ORDINATE="$file/Foto" crea_cartelle_mancanti
            rc copy "$file/Foto" "$DESTINAZIONE" --ignore-existing \
                --transfers "$paralleli" --checkers $((paralleli + 8)) \
                --stats-one-line --stats 30s --stats-log-level NOTICE
            rc check "$file/Foto" "$DESTINAZIONE" --one-way --size-only \
                || errore "su $DESTINAZIONE manca qualcosa del lotto o c'è un file diverso con lo stesso nome: rilancia $0 carica"
        fi
        ORDINATE="$file" DESTINAZIONE="$pord" crea_cartelle_mancanti
        rc copy "$file" "$pord" --ignore-existing \
            --transfers "$paralleli" --checkers $((paralleli + 8)) \
            --stats-one-line --stats 30s --stats-log-level NOTICE
        rc check "$file" "$pord" --one-way --size-only \
            || errore "su $pord manca qualcosa del lotto o c'è un file diverso con lo stesso nome: rilancia $0 carica"
    fi
    touch "$ATTESA/registro.tsv" "$ATTESA/impronte.tsv" "$ATTESA/fatti.tsv"
    cat "$ATTESA/registro.tsv" >> "$REGISTRO"
    cat "$ATTESA/impronte.tsv" >> "$IMPRONTE"
    cat "$ATTESA/fatti.tsv" >> "$FATTI"
    rm -rf "$ATTESA"
    return 0
}

prepara_lavoro() {
    richiede "$RCLONE" exiftool unzip zip sha256sum
    controlla_impostazioni
    salva_impostazioni
    touch "$REGISTRO" "$IMPRONTE" "$FATTI"
    carica_altri_lavori
    carica_miei
    IN_ATTESA_VOCI=0 IN_ATTESA_BYTE=0 NON_SCARICATI=0
}

ordina() {
    prepara_lavoro
    elenca
    mostra_anteprima
    if [ "${#VOCI[@]}" -eq 0 ] && [ ! -d "$ATTESA" ]; then
        riepilogo
        return 0
    fi
    local risposta=""
    read -r -p "Scrivi ORDINA per cominciare: " risposta || true
    if [ "$risposta" != ORDINA ]; then
        echo "Non ho fatto niente."
        exit 0
    fi
    # Quello che un giro interrotto ha lasciato a metà in TMP si rifà da capo.
    rm -rf "$TMP" 2>/dev/null || true
    carica_attesa
    carica_miei
    elenca_ordinati
    in_fila "${#VOCI[@]}" titolo_voce prepara_voce applica_voce
    carica_attesa
    rm -rf "$TMP"
    if [ "${#CONTO[@]}" -gt 0 ]; then
        echo "In questo giro:"
        local e
        for e in "${!CONTO[@]}"; do printf '    %6s %s\n' "${CONTO[$e]}" "$e"; done | sort -k2
    fi
    if [ "$NON_SCARICATI" -gt 0 ]; then
        echo "$NON_SCARICATI file non si sono scaricati: rilancia $0 ordina"
    else
        date +%s > "$LAVORO/fase-ordina.ok"
    fi
    riepilogo
}

carica() {
    prepara_lavoro
    if [ ! -d "$ATTESA" ]; then
        echo "Niente in attesa."
        return 0
    fi
    carica_attesa
    riepilogo
}

riepilogo() {
    echo "Registro: $REGISTRO"
    if [ -s "$REGISTRO" ]; then
        cut -f5 "$REGISTRO" | sort | uniq -c | sed 's/^ */    /'
    fi
    if grep -q $'\tnuova su FotoTimeline$' "$REGISTRO" 2>/dev/null; then
        echo "Foto e video nuovi in $DESTINAZIONE: ora nell'app Monta, poi Indicizza archivio."
    fi
    if grep -q $'\tirrecuperabile$' "$REGISTRO" 2>/dev/null; then
        echo "Irrecuperabili (restano solo negli zip di $RIMASTI): grep irrecuperabile '$REGISTRO'"
    fi
    return 0
}

stato() {
    if [ -z "$RADICE" ] || [ -z "$PCLOUD" ]; then
        echo "Lavoro:      $LAVORO (impostazioni non ancora trovate: $0 anteprima)"
        return 0
    fi
    controlla_impostazioni
    echo "Lavoro:      $LAVORO"
    echo "Da guardare: $RIMASTI"
    echo "Ordinati:    $ORDINATI"
    echo "Destinaz.:   $DESTINAZIONE"
    carica_fatti
    if [ -s "$ELENCO" ]; then
        echo "Fatti:       ${#FATTO[@]} file su $(wc -l < "$ELENCO") (all'ultimo elenco), $(grep -c $'\tirrecuperabile$' "$FATTI" 2>/dev/null || true) zip irrecuperabili"
    else
        echo "Fatti:       ${#FATTO[@]} file (elenco non ancora letto: $0 anteprima)"
    fi
    if [ -d "$ATTESA" ]; then
        echo "In attesa:   $(wc -l 2>/dev/null < "$ATTESA/fatti.tsv" || echo 0) file da caricare: $0 carica (o ordina)"
    fi
    if [ -f "$LAVORO/fase-ordina.ok" ]; then echo "Finito:      $(data_di "$LAVORO/fase-ordina.ok")"; else echo "Finito:      -"; fi
    riepilogo
}

# Un giro solo alla volta: due ordina insieme scaricherebbero e caricherebbero gli stessi
# file (e il secondo cancella il TMP del primo). Il lucchetto passa ai figli (anche rclone):
# finché uno di loro gira, un altro ordina o carica non parte.
un_giro_solo() {
    command -v flock >/dev/null || return 0
    mkdir -p "$LAVORO"
    exec 9>"$LAVORO/in-corso.lock"
    flock -n 9 || errore "c'è già un giro in corso (pgrep -af ordina-da-controllare.sh): aspetta che finisca, o fermalo prima di rilanciare"
}

case "$FASE" in
    anteprima) anteprima ;;
    ordina) un_giro_solo; ordina ;;
    carica) un_giro_solo; carica ;;
    stato) stato ;;
    *) errore "fase sconosciuta: $FASE (anteprima, ordina, carica, stato)" ;;
esac
