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
#   foto-da-pcloud.sh metti-da-parte
#                                sposta su pCloud, in RIMASTI, quelli rimasti in da-controllare.txt
#                                (zip con altri file, file non letti, zip rovinati), per guardarli
#                                tutti insieme. Chiede SPOSTA, non cancella niente
#   foto-da-pcloud.sh stato      a che punto è
#
# La prima volta (poi si ricordano in LAVORO/impostazioni):
#   RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' foto-da-pcloud.sh anteprima
#   ESCLUDI='TASSE:banche:avvocato' foto-da-pcloud.sh anteprima     # finché torna
#   foto-da-pcloud.sh scarica
#   foto-da-pcloud.sh raccogli
# ESCLUDI: cartelle da saltare, relative a RADICE e separate da ":" (le scansioni
# in JPG sono foto per lo script: le cartelle di documenti vanno escluse). Si
# saltano sempre ARCHIVIO, RIMASTI, "Crypto Folder" e le cartelle di sistema di Windows.
# Più radici: un raccogli per radice, prima di carica; i doppioni tra una e
# l'altra entrano una volta sola.
#
# PARALLELI: in raccogli, quanti zip (o blocchi di 500 file sciolti) si preparano insieme,
# cioè si aprono, si leggono le date e si calcolano le impronte (predefinito 4, come i core
# del PC; PARALLELI=1 tutto in fila, come prima). Si mettono in ordinate/ comunque uno alla
# volta e nello stesso ordine: stessi file e stessi nomi con qualsiasi PARALLELI. Su disco
# restano aperti al massimo PARALLELI+1 zip insieme. Per esempio: PARALLELI=2 foto-da-pcloud.sh raccogli
# CARICA_PARALLELI: in carica, quanti file si mandano insieme a LifetimeCloud (predefinito
# 16). Per esempio: CARICA_PARALLELI=8 foto-da-pcloud.sh carica
# Prima di copiare, carica crea una alla volta le cartelle AAAA/MM/GG che mancano: se due
# file in parallelo creano insieme la stessa cartella, LifetimeCloud ne fa due con lo stesso
# nome e i file di una non si vedono più. Se trova cartelle doppie già fatte si ferma: prima
# deploy/sistema-cartelle-doppie.sh, che le riunisce senza cancellare niente.
#
# Ogni fase si può rilanciare: riprende da dove era arrivata. Pulisci cancella solo
# gli originali elencati in LAVORO/fonti.tsv come copiati per intero; gli zip con
# dentro anche altri file (documenti, ...) e i file non letti restano dove sono,
# elencati in LAVORO/da-controllare.txt. Dopo carica, nell'app: "Indicizza archivio".
#
# metti-da-parte li sposta tutti in RIMASTI (predefinita "RADICE/Da controllare", si ricorda
# in impostazioni), ognuno col suo percorso sotto RADICE: /mnt/p/Backups/x.zip finisce in
# "Da controllare/Backups/x.zip". Prima dice quanti sono e quanto pesano e chiede SPOSTA.
# Con scarica fatto sposta con rclone sul remote PCLOUD (lato server, niente download),
# se no con mv su P:. Un file che a destinazione c'è già non si sovrascrive, uno che su
# pCloud non c'è più si salta; tutto finisce in LAVORO/messi-da-parte.tsv, così rilanciato
# rifà solo quelli non ancora spostati. RIMASTI poi si salta sempre, come ARCHIVIO.
#
# Serve: unzip, zip, exiftool, rclone (sudo apt install unzip zip libimage-exiftool-perl rclone).
set -euo pipefail

FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato.
DATI_RADICE="${RADICE:-}" DATI_ARCHIVIO="${ARCHIVIO:-}" DATI_DESTINAZIONE="${DESTINAZIONE:-}" DATI_GIORNI="${GIORNI:-}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}" DATI_PCLOUD="${PCLOUD:-}" DATI_RIMASTI="${RIMASTI:-}"
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
# Dove metti-da-parte mette i file rimasti: relativa vuol dire sotto RADICE.
RIMASTI="${DATI_RIMASTI:-${RIMASTI:-}}"
if [ -n "$RADICE" ]; then
    RIMASTI="${RIMASTI:-Da controllare}"
    case "$RIMASTI" in /*) ;; *) RIMASTI="${RADICE%/}/$RIMASTI" ;; esac
    [ "$RIMASTI" != / ] && RIMASTI="${RIMASTI%/}"
fi
RCLONE="${RCLONE:-rclone}"
# File scaricati in parallelo da pCloud: con tante foto piccole il limite è l'attesa per
# file, non la banda (8 -> 24 ha portato una fibra da 8 a 29 MB/s).
TRASFERIMENTI="${TRASFERIMENTI:-24}"
# Nomi di file più lunghi di così (in byte) si scaricano accorciati a NOME_CORTO byte.
NOME_MAX="${NOME_MAX:-255}"
NOME_CORTO="${NOME_CORTO:-180}"
export TZ="${TZ:-Europe/Rome}"

ORDINATE="$LAVORO/ordinate"
IMPRONTE="$LAVORO/impronte.tsv"          # sha256 \t percorso in ordinate/
MANIFEST="$LAVORO/manifest.tsv"          # fonte \t file nello zip (o vuoto) \t sha256 \t percorso in ordinate/
FONTI="$LAVORO/fonti.tsv"                # file|zip \t fonte \t si|no (si = si può cancellare)
DA_CONTROLLARE="$LAVORO/da-controllare.txt"
ARCHIVIATI="$LAVORO/archiviati.tsv"      # AAAA/MM \t zip su pCloud
MESSI_DA_PARTE="$LAVORO/messi-da-parte.tsv"  # origine \t destinazione in RIMASTI \t esito
RADICI="$LAVORO/radici.txt"              # le cartelle raccolte, una per riga
TMP="$LAVORO/tmp"
SPECCHIO="$LAVORO/specchio"              # copia locale di RADICE fatta da "scarica"
# Le funzioni comuni (raccogliere, caricare, archiviare) stanno in lib-foto.sh, accanto.
DOVE_ORIGINE="su pCloud" DOVE_ARCHIVIO="su pCloud" DATA_DAL_NOME="" ZIP_SUL_POSTO=""
COPIE_SCARICATE=""
SCARTA_NEI_ZIP=()
# shellcheck source=lib-foto.sh
. "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/lib-foto.sh"

# Il find delle radici: salta l'archivio, i file messi da parte (RIMASTI, anche nella
# copia locale), le cartelle escluse e quelle di sistema.
trova() {
    local radice="$1"; shift
    local -a salta=(-path "$ARCHIVIO" -o -name 'Crypto Folder' -o -name 'System Volume Information'
        -o -name '$RECYCLE.BIN' -o -name '.Trash*' -o -name '.pcloud*')
    if [ -n "$RIMASTI" ]; then
        salta+=(-o -path "$RIMASTI")
        [ "${RIMASTI#"$RADICE"/}" != "$RIMASTI" ] && salta+=(-o -path "$radice/${RIMASTI#"$RADICE"/}")
    fi
    local x
    local IFS=:
    for x in $ESCLUDI; do
        [ -n "$x" ] && salta+=(-o -path "$radice/${x%/}")
    done
    unset IFS
    find "$radice" \( "${salta[@]}" \) -prune -o "$@" 2>/dev/null
}

# ARCHIVIO è una cartella sua: né la radice né una cartella che la contiene.
controlla_archivio() {
    case "$RADICE/" in
        "$ARCHIVIO"/*) errore "ARCHIVIO ($ARCHIVIO) non può essere la radice o contenerla: usa una cartella nuova, per esempio '$RADICE/Archivio foto'" ;;
    esac
}

salva_impostazioni() {
    printf 'RADICE=%q\nARCHIVIO=%q\nESCLUDI=%q\nDESTINAZIONE=%q\nGIORNI=%q\nPCLOUD=%q\nRIMASTI=%q\n' \
        "$RADICE" "$ARCHIVIO" "$ESCLUDI" "$DESTINAZIONE" "$GIORNI" "$PCLOUD" "$RIMASTI" > "$IMPOSTAZIONI"
}

# RIMASTI è una cartella dentro RADICE (così rclone la raggiunge dallo stesso remote), non la
# radice stessa, e non ha a che fare con ARCHIVIO.
controlla_rimasti() {
    case "$RIMASTI/" in
        "$RADICE"/?*/) ;;
        *) errore "RIMASTI ($RIMASTI) dev'essere una cartella dentro RADICE ($RADICE), per esempio '$RADICE/Da controllare'" ;;
    esac
    case "$RIMASTI/" in "$ARCHIVIO"/*) errore "RIMASTI ($RIMASTI) non può stare in ARCHIVIO ($ARCHIVIO)" ;; esac
    case "$ARCHIVIO/" in "$RIMASTI"/*) errore "ARCHIVIO ($ARCHIVIO) non può stare in RIMASTI ($RIMASTI)" ;; esac
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
    local da_parte=""
    [ -d "$RIMASTI" ] && da_parte="${RIMASTI#"$RADICE"/}, "
    echo "Salto: ${ARCHIVIO#"$RADICE"/}, ${da_parte}Crypto Folder, cartelle di sistema${ESCLUDI:+, $ESCLUDI}"
    conta_media "$RADICE"
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
        rel="${RIMASTI#"$RADICE"/}"
        [ -n "$RIMASTI" ] && [ "$rel" != "$RIMASTI" ] && printf -- '- /%s/**\n' "$rel"
        printf -- '- Crypto Folder/**\n- System Volume Information/**\n- $RECYCLE.BIN/**\n- .Trash*/**\n- .pcloud*/**\n'
        local IFS=:
        for x in $ESCLUDI; do [ -n "$x" ] && printf -- '- /%s/**\n' "${x%/}"; done
        unset IFS
        printf -- '+ *.zip\n'
        for x in "${ESTENSIONI[@]}"; do printf -- '+ *.%s\n' "$x"; done
        printf -- '- *\n'
    } > "$filtri"
    mkdir -p "$SPECCHIO"
    local lunghi
    lunghi="$(nomi_lunghi "$filtri")"
    echo "Scarico da $PCLOUD in $SPECCHIO (si può interrompere e rilanciare)…"
    rc copy "$PCLOUD" "$SPECCHIO" --filter-from "${lunghi:-$filtri}" --ignore-case \
        --transfers "$TRASFERIMENTI" --checkers $((TRASFERIMENTI + 8)) --stats-one-line --stats 30s --stats-log-level NOTICE
    [ -n "$lunghi" ] && scarica_nomi_lunghi
    date +%s > "$LAVORO/fase-scarica.ok"
    echo "Scaricato: $(du -sh "$SPECCHIO" | cut -f1). Prossimo passo: $0 raccogli"
}

# Nomi di file oltre NOME_MAX byte: pCloud li accetta, il disco di Linux (255) no, e rclone
# si fermerebbe con "file name too long". Li elenca in nomi-lunghi-remoti.txt e stampa un
# filtro che li salta (vuoto se non ce ne sono): si scaricano a parte, accorciati.
nomi_lunghi() {
    local filtri="$1" elenco="$LAVORO/nomi-lunghi-remoti.txt" con="$LAVORO/filtri-scarica-lunghi.txt"
    rc lsf -R --files-only --filter-from "$filtri" --ignore-case "$PCLOUD" \
        | LC_ALL=C awk -F/ -v max="$NOME_MAX" 'length($NF) > max' > "$elenco.nuovo"
    if [ ! -s "$elenco.nuovo" ]; then
        rm -f "$elenco.nuovo" "$con"
        return
    fi
    mv "$elenco.nuovo" "$elenco"
    echo "$(wc -l < "$elenco") file con il nome troppo lungo: li scarico a parte, accorciati." >&2
    {
        # Prima regola che corrisponde vince: le esclusioni in testa. Nei filtri di
        # rclone * ? [ ] { } \ vanno protetti.
        sed 's/[][\\*?{}]/\\&/g; s|^|- /|' "$elenco"
        cat "$filtri"
    } > "$con"
    printf '%s' "$con"
}

# Il nome accorciato: al massimo NOME_CORTO byte (senza spezzare le lettere accentate),
# poi ~ e 8 cifre dell'impronta del percorso originale, poi l'estensione.
nome_corto() {
    local nome="$1" rel="$2" ext="" base
    base="$nome"
    case "$nome" in *.*) ext=".${nome##*.}"; base="${nome%.*}" ;; esac
    base="$(printf '%s' "$base" | head -c "$NOME_CORTO" | iconv -f UTF-8 -t UTF-8 -c)"
    printf '%s~%s%s' "$base" "$(printf '%s' "$rel" | sha1sum | cut -c1-8)" "$ext"
}

# Scarica i nomi lunghi accorciati; nomi-lunghi.tsv (nome in specchio \t nome su pCloud)
# serve a raccogli e pulisci per ritrovare l'originale.
scarica_nomi_lunghi() {
    local elenco="$LAVORO/nomi-lunghi-remoti.txt" tsv="$LAVORO/nomi-lunghi.tsv" rel dir corto da
    touch "$tsv"
    while IFS= read -r rel; do
        dir="$(dirname "$rel")"
        corto="$(nome_corto "$(basename "$rel")" "$rel")"
        [ "$dir" != . ] && corto="$dir/$corto"
        case "$PCLOUD" in *:) da="$PCLOUD$rel" ;; *) da="$PCLOUD/$rel" ;; esac
        rc copyto "$da" "$SPECCHIO/$corto" </dev/null
        grep -Fq -- "$corto"$'\t' "$tsv" || printf '%s\t%s\n' "$corto" "$rel" >> "$tsv"
        echo "  accorciato: $corto"
    done < "$elenco"
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
    echo "Cerco foto, video e zip in $BASE (salto $ARCHIVIO)…"
    raccogli_fonti
}

carica() {
    carica_ordinate
    echo "Prossimo passo: $0 archivia"
}

archivia() {
    archivia_mesi
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
        # Quello messo da parte non si tocca (lì ci sono solo "no", ma meglio esserne certi).
        if [ -n "$RIMASTI" ] && [[ "$fonte" == "$RIMASTI"/* ]]; then continue; fi
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
    rc delete "$PCLOUD" --files-from-raw "$lista" --stats-one-line --stats 30s --stats-log-level NOTICE
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

# I file rimasti su pCloud, quelli di da-controllare.txt, tutti insieme in RIMASTI: lì si
# guardano a mano. Il percorso nelle righe è già quello vero sotto RADICE, anche se raccogli
# ha letto dalla copia locale: lo scrivono applica_zip e applica_sciolti con fonte_in, che
# toglie specchio/ e rimette il nome lungo di pCloud al posto di quello accorciato da scarica.
# Le righe rientrate di 4 spazi (nomi d'esempio delle versioni vecchie) non contano.
metti_da_parte() {
    [ -n "$RADICE" ] || errore "manca RADICE: prima $0 anteprima"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    controlla_rimasti
    [ -s "$DA_CONTROLLARE" ] || { echo "Niente da mettere da parte: $DA_CONTROLLARE è vuoto o non c'è."; exit 0; }
    # Con scarica fatto si sposta con rclone, lato server; se no con mv su P:.
    local con_rclone=""
    [ -f "$LAVORO/fase-scarica.ok" ] && [ -n "$PCLOUD" ] && con_rclone=1
    if [ -n "$con_rclone" ]; then
        richiede "$RCLONE"
    else
        [ -d "$RADICE" ] || errore "$RADICE non c'è (pCloud montato? sudo mount -t drvfs P: /mnt/p)"
    fi
    salva_impostazioni
    local dentro="${RIMASTI#"$RADICE"/}"   # RIMASTI relativa a RADICE, cioè al remote PCLOUD

    # Il registro: origine \t destinazione \t esito. Quello "spostato" non si rifà.
    local -A ESITO=()
    local o e riga
    touch "$MESSI_DA_PARTE"
    while IFS=$'\t' read -r o _ e; do [ -n "$o" ] && ESITO[$o]="$e"; done < "$MESSI_DA_PARTE"

    local re_zip='^zip con [0-9]+ altri file, resta su pCloud: (.+)$'
    local re_letto='^non letto: (.+)$'
    local re_rotto='^zip rovinato \(unzip [0-9]+\): (.+)$'
    local -A VISTA=() TIPO=()
    local -a ordine=() rels=() fuori=()
    local f tipo prima=0
    while IFS= read -r riga; do
        if [[ "$riga" =~ $re_zip ]]; then tipo=zip
        elif [[ "$riga" =~ $re_letto ]]; then tipo=letto
        elif [[ "$riga" =~ $re_rotto ]]; then tipo=rotto
        else continue
        fi
        f="${BASH_REMATCH[1]}"
        [ -n "${VISTA[$f]:-}" ] && continue
        VISTA[$f]=1
        # Da un'altra radice (radici.txt): RIMASTI è sotto questa, quelli restano dove sono.
        if [[ "$f" != "$RADICE"/* ]] || [[ "$f" == "$RIMASTI"/* ]]; then fuori+=("$f"); continue; fi
        ordine+=("$f")
        if [ "${ESITO[$f]:-}" = spostato ]; then prima=$((prima + 1)); continue; fi
        rels+=("${f#"$RADICE"/}")
        TIPO[${f#"$RADICE"/}]="$tipo"
    done < "$DA_CONTROLLARE"

    # Dove sono adesso: l'origine c'è ancora? A destinazione c'è già un file con quel nome?
    local origini="$LAVORO/da-spostare-origini.txt" arrivi="$LAVORO/da-spostare-arrivi.txt" rel
    local -A PESO=() ARRIVO=()
    printf '%s\n' "${rels[@]}" | sed '/^$/d' > "$origini"
    sed "s|^|$(printf '%s' "$dentro" | sed 's/[\\|&]/\\&/g')/|" "$origini" > "$arrivi"
    presenti PESO "$origini"
    presenti ARRIVO "$arrivi"

    local -a sposta=() doppi=() mancano=()
    local n_zip=0 n_letti=0 n_rotti=0 peso=0 p
    for rel in "${rels[@]}"; do
        if [ -n "${PESO[$rel]+c}" ]; then
            if [ -n "${ARRIVO[$dentro/$rel]+c}" ]; then
                doppi+=("$rel"); ESITO[$RADICE/$rel]="c'è già a destinazione, non sovrascritto"
            else
                sposta+=("$rel"); p="${PESO[$rel]}"; peso=$((peso + p))
                case "${TIPO[$rel]}" in zip) n_zip=$((n_zip + 1)) ;; letto) n_letti=$((n_letti + 1)) ;; *) n_rotti=$((n_rotti + 1)) ;; esac
            fi
        elif [ -n "${ARRIVO[$dentro/$rel]+c}" ]; then
            # Spostato da un giro interrotto prima di scrivere il registro.
            ESITO[$RADICE/$rel]=spostato; prima=$((prima + 1))
        else
            mancano+=("$rel"); ESITO[$RADICE/$rel]="non c'è più su pCloud"
        fi
    done

    if [ "${#doppi[@]}" -gt 0 ]; then
        echo "Salto ${#doppi[@]} file: in $RIMASTI c'è già un file con lo stesso nome (non lo sovrascrivo):"
        printf '    %s\n' "${doppi[@]:0:10}"
    fi
    if [ "${#mancano[@]}" -gt 0 ]; then
        echo "Salto ${#mancano[@]} file che su pCloud non ci sono più:"
        printf '    %s\n' "${mancano[@]:0:10}"
    fi
    if [ "${#fuori[@]}" -gt 0 ]; then
        echo "Salto ${#fuori[@]} file fuori da $RADICE (o già in $RIMASTI): restano dove sono"
        printf '    %s\n' "${fuori[@]:0:10}"
    fi
    [ "$prima" -gt 0 ] && echo "Già messi da parte: $prima file."
    if [ "${#sposta[@]}" -eq 0 ]; then
        echo "Niente da spostare."
        scrivi_messi_da_parte
        riepilogo_messi_da_parte
        rm -f "$origini" "$arrivi"
        return
    fi
    echo "Sposto in $RIMASTI ${#sposta[@]} file ($(numfmt --to=iec --suffix=B "$peso")):" \
        "$n_zip zip con altri file, $n_letti non letti, $n_rotti zip rovinati. Per esempio:"
    printf '    %s\n' "${sposta[@]:0:10}"
    echo "(ognuno col suo percorso: ${sposta[0]} -> $dentro/${sposta[0]})"
    local risposta=""
    read -r -p "Scrivi SPOSTA per confermare: " risposta || true
    if [ "$risposta" != SPOSTA ]; then
        echo "Non ho spostato niente."
        rm -f "$origini" "$arrivi"
        exit 0
    fi

    printf '%s\n' "${sposta[@]}" > "$origini"
    sed "s|^|$(printf '%s' "$dentro" | sed 's/[\\|&]/\\&/g')/|" "$origini" > "$arrivi"
    if [ -n "$con_rclone" ]; then
        # Un solo rclone per tutti: con --files-from sposta file per file (mai una cartella
        # intera), lato server, e crea le cartelle; --ignore-existing: mai sovrascrivere.
        echo "Sposto con rclone su $PCLOUD…"
        rc move "$PCLOUD" "$(remoto_di "$RIMASTI")" --files-from-raw "$origini" --ignore-existing \
            --transfers "$TRASFERIMENTI" --stats-one-line --stats 30s --stats-log-level NOTICE || echo "rclone non è finito bene: controllo cosa si è spostato." >&2
    else
        echo "Sposto su $RADICE…"
        for rel in "${sposta[@]}"; do
            if mkdir -p "$(dirname "$RIMASTI/$rel")"; then mv -n -- "$RADICE/$rel" "$RIMASTI/$rel" || true; fi
        done
    fi
    # Spostato è solo quello che ora è a destinazione e non più all'origine.
    PESO=() ARRIVO=()
    presenti PESO "$origini"
    presenti ARRIVO "$arrivi"
    local male=0
    for rel in "${sposta[@]}"; do
        if [ -n "${ARRIVO[$dentro/$rel]+c}" ] && [ -z "${PESO[$rel]+c}" ]; then
            ESITO[$RADICE/$rel]=spostato
        else
            ESITO[$RADICE/$rel]="non spostato"; male=$((male + 1))
        fi
    done
    scrivi_messi_da_parte
    rm -f "$origini" "$arrivi"
    riepilogo_messi_da_parte
    [ "$male" -eq 0 ] || errore "$male file non si sono spostati (\"non spostato\" in $MESSI_DA_PARTE): rilancia $0 metti-da-parte"
}

# presenti MAPPA LISTA: per i percorsi della LISTA (relativi a RADICE) che ci sono, MAPPA[percorso]
# = dimensione. Con rclone un solo lsf, che guarda solo quei file.
# shellcheck disable=SC2004,SC2034
presenti() {
    local -n mappa="$1"
    local lista="$2" peso rel
    [ -s "$lista" ] || return 0
    if [ -n "$con_rclone" ]; then
        local uscita
        uscita="$(rc lsf -R --files-only --files-from-raw "$lista" --format sp --separator $'\t' "$PCLOUD")" \
            || errore "non riesco a leggere $PCLOUD: rilancia $0 metti-da-parte"
        while IFS=$'\t' read -r peso rel; do
            [ -n "$rel" ] && mappa[$rel]="$peso"
        done <<< "$uscita"
    else
        while IFS= read -r rel; do
            [ -f "$RADICE/$rel" ] && mappa[$rel]="$(stat -c %s -- "$RADICE/$rel")"
        done < "$lista"
    fi
    return 0
}

# Il registro di nuovo, nell'ordine di da-controllare.txt (vedono ESITO, ordine, dentro).
scrivi_messi_da_parte() {
    local o
    for o in "${ordine[@]}"; do
        [ -n "${ESITO[$o]:-}" ] && printf '%s\t%s\t%s\n' "$o" "$RIMASTI/${o#"$RADICE"/}" "${ESITO[$o]}"
    done > "$MESSI_DA_PARTE.nuovo"
    mv "$MESSI_DA_PARTE.nuovo" "$MESSI_DA_PARTE"
}

riepilogo_messi_da_parte() {
    echo "In $MESSI_DA_PARTE:"
    cut -f3 "$MESSI_DA_PARTE" | sort | uniq -c | sed 's/^ */    /'
    [ "${#fuori[@]}" -gt 0 ] && echo "    ${#fuori[@]} fuori da $RADICE, non toccati"
    grep -q $'\tspostato$' "$MESSI_DA_PARTE" && echo "I file messi da parte sono in $RIMASTI."
    return 0
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
    if [ -s "$MESSI_DA_PARTE" ]; then
        echo "Da parte:    $(grep -c $'\tspostato$' "$MESSI_DA_PARTE" || true) file in $RIMASTI"
    fi
}

case "$FASE" in
    anteprima) anteprima ;;
    scarica) scarica ;;
    raccogli) raccogli ;;
    carica) carica ;;
    archivia) archivia ;;
    pulisci) pulisci ;;
    metti-da-parte) metti_da_parte ;;
    stato) stato ;;
    *) errore "fase sconosciuta: $FASE (anteprima, scarica, raccogli, carica, archivia, pulisci, metti-da-parte, stato)" ;;
esac
