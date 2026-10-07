#!/usr/bin/env bash
# Riunisce le cartelle doppie di LifetimeCloud, senza cancellare niente.
#
# LifetimeCloud, se due caricamenti in parallelo creano insieme la stessa cartella, ne fa
# due (o tre) con lo stesso nome. rclone ("Duplicate directory found ... ignoring") e l'app
# ne vedono una sola: i file delle altre non si vedono. Su WebDAV le copie hanno lo stesso
# percorso, quindi un MOVE ne sposta una alla volta. Per ogni cartella doppia:
#   1. sposta in DOPPIE una copia alla volta (MOVE lato server, rclone moveto), come
#      2015-11-1, 2015-11-2, ..., finché in DESTINAZIONE non ne resta nessuna; dopo ogni
#      spostamento controlla che ne sia sparita una sola, se no si ferma;
#   2. se dentro una copia spostata ci sono altre cartelle doppie, le separa allo stesso modo;
#   3. ricrea la cartella una volta sola e ci ricopia (COPY lato server) il contenuto di tutte
#      le copie, un file alla volta e senza sovrascrivere: i file uguali si saltano, un file
#      con lo stesso nome e un'altra dimensione resta solo in DOPPIE e viene elencato;
#   4. controlla che in DESTINAZIONE non manchi niente e non ci siano più doppioni.
# DOPPIE resta com'è: la si butta a mano dopo un controllo. Da fare con carica fermo e senza
# usare l'app nel frattempo: per qualche minuto le foto di quelle cartelle non si vedono.
#
#   sistema-cartelle-doppie.sh                         elenca le cartelle doppie, non tocca niente
#   sistema-cartelle-doppie.sh sistema                 le cerca e le sistema tutte
#   sistema-cartelle-doppie.sh sistema 2015/11 2015/12 solo queste (relative a DESTINAZIONE)
#
# Cercarle legge tutte le cartelle di DESTINAZIONE (qualche minuto); con i percorsi dati no.
# Impostazioni: DESTINAZIONE (predefinita lifetime:FotoTimeline), DOPPIE (predefinita
# DESTINAZIONE-doppie), REGISTRO (predefinito ~/cartelle-doppie.tsv: le copie spostate e
# ricopiate; se si interrompe, rilanciato riprende da lì), RCLONE.
set -euo pipefail

FASE="${1:-elenca}"
[ $# -gt 0 ] && shift
DESTINAZIONE="${DESTINAZIONE:-lifetime:FotoTimeline}"
DESTINAZIONE="${DESTINAZIONE%/}"
DOPPIE="${DOPPIE:-${DESTINAZIONE}-doppie}"
DOPPIE="${DOPPIE%/}"
REGISTRO="${REGISTRO:-$HOME/cartelle-doppie.tsv}"
RCLONE="${RCLONE:-rclone}"

errore() { echo "Errore: $*" >&2; exit 1; }
rc() { "$RCLONE" "$@"; }
command -v "$RCLONE" >/dev/null || errore "manca $RCLONE"
case "$DOPPIE/" in
    "$DESTINAZIONE/"*) errore "DOPPIE ($DOPPIE) non può stare dentro DESTINAZIONE ($DESTINAZIONE)" ;;
esac
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT
touch "$REGISTRO"

# Le cartelle doppie vere in un elenco di rclone lsf -R --dirs-only (righe "a/b/"), una
# per riga con quante copie: "2015/11<TAB>3". rclone entra in ogni copia con lo stesso
# percorso, cioè sempre nella stessa, quindi sotto una cartella tripla ogni sottocartella
# compare tre volte: è doppia davvero solo se compare più volte della sua madre. Si danno
# solo le più in alto: quelle dentro si vedono dopo, nelle copie spostate.
doppie_in() {
    awk '{ sub(/\/$/, ""); n[$0]++ }
        END {
            for (p in n) {
                q = p
                if (!sub(/\/[^\/]*$/, "", q)) q = ""
                madre = (q in n) ? n[q] : 1
                if (n[p] > madre) vera[p] = int(n[p] / madre)
            }
            for (p in vera) {
                q = p; sotto = 0
                while (sub(/\/[^\/]*$/, "", q)) if (q in vera) { sotto = 1; break }
                if (!sotto) printf "%s\t%d\n", p, vera[p]
            }
        }' | LC_ALL=C sort
}

# Quante cartelle si chiamano NOME dentro MADRE (percorsi di rclone).
quante() {
    local elenco
    elenco="$(rc lsf --dirs-only "$1")" || errore "non riesco a leggere $1"
    printf '%s\n' "$elenco" | grep -cxF -- "$2/" || true
}
# Quante copie ci sono in DESTINAZIONE della cartella REL (come 2015/11).
copie_di() {
    local madre="$DESTINAZIONE"
    [[ "$1" == */* ]] && madre="$DESTINAZIONE/${1%/*}"
    quante "$madre" "${1##*/}"
}

# Un nome nuovo in DOPPIE per una copia di ORIGINALE: 2015/11 -> DOPPIE/2015-11-1, -2, ...
nuovo_pezzo() {
    local elenco e=0 i=1 base
    elenco="$(rc lsf --dirs-only "$DOPPIE" 2>/dev/null)" || e=$?
    # 3: DOPPIE non c'è ancora (la crea il primo spostamento).
    [ "$e" -eq 0 ] || [ "$e" -eq 3 ] || errore "non riesco a leggere $DOPPIE"
    base="${1//\//-}"
    while printf '%s\n' "$elenco" | grep -qxF -- "$base-$i/"; do i=$((i + 1)); done
    printf '%s/%s-%s' "$DOPPIE" "$base" "$i"
}

# separa REMOTO ORIGINALE: sposta in DOPPIE tutte le copie di REMOTO, una alla volta, e le
# segna nel REGISTRO con ORIGINALE (dove vanno ricopiate, relativo a DESTINAZIONE). Poi fa
# lo stesso con le cartelle doppie dentro ogni copia spostata.
separa() {
    local remoto="$1" originale="$2" madre nome n dopo pezzo riga interne
    local -a pezzi=()
    madre="${remoto%/*}"; nome="${remoto##*/}"
    n="$(quante "$madre" "$nome")"
    while [ "$n" -gt 0 ]; do
        pezzo="$(nuovo_pezzo "$originale")"
        if [ "$n" -gt 1 ]; then echo "  $originale: sposto una delle $n copie in $pezzo"
        else echo "  $originale: sposto l'ultima copia in $pezzo"; fi
        # -v per sapere com'è andata: deve essere un MOVE lato server della cartella intera.
        # Se rclone ripiegasse sui file uno per uno, sposterebbe comunque soltanto (niente si
        # cancella), ma qui ci si ferma.
        if ! rc moveto "$remoto" "$pezzo" -v > "$TMPD/sposta" 2>&1; then
            cat "$TMPD/sposta" >&2
            errore "lo spostamento di $remoto in $pezzo non è riuscito: guarda le due cartelle prima di rilanciare"
        fi
        if ! grep -q 'Server side directory move succeeded' "$TMPD/sposta"; then
            cat "$TMPD/sposta" >&2
            errore "rclone non ha spostato $remoto lato server: guarda $remoto e $pezzo prima di andare avanti"
        fi
        printf 'spostata\t%s\t%s\n' "$originale" "$pezzo" >> "$REGISTRO"
        pezzi+=("$pezzo")
        dopo="$(quante "$madre" "$nome")"
        [ "$dopo" -eq $((n - 1)) ] \
            || errore "$remoto: prima $n copie, dopo lo spostamento $dopo (ne doveva sparire una): guarda $remoto e $DOPPIE prima di rilanciare"
        n="$dopo"
    done
    for pezzo in "${pezzi[@]}"; do
        interne="$(elenca_doppie "$pezzo")"
        while IFS=$'\t' read -r riga _; do
            [ -z "$riga" ] || separa "$pezzo/$riga" "$originale/$riga"
        done <<< "$interne"
    done
}

# elenca_doppie REMOTO: le cartelle doppie dentro REMOTO (doppie_in).
elenca_doppie() {
    local elenco
    elenco="$(rc lsf -R --dirs-only "$1")" || errore "non riesco a leggere le cartelle di $1"
    printf '%s\n' "$elenco" | doppie_in
}

# Le copie spostate non ancora ricopiate, nell'ordine del REGISTRO: prima la cartella, poi
# quelle dentro. Le ricrea una volta sola (rclone mkdir, una alla volta) e ci ricopia tutto
# con un file alla volta: con un solo caricamento alla volta nessuna cartella si raddoppia.
ricopia() {
    local riga tipo originale pezzo e
    local -A fatta=()
    local -a righe=()
    mapfile -t righe < "$REGISTRO"
    for riga in "${righe[@]}"; do
        IFS=$'\t' read -r tipo originale pezzo <<< "$riga"
        if [ "$tipo" = ricopiata ]; then fatta[$pezzo]=1; fi
    done
    for riga in "${righe[@]}"; do
        IFS=$'\t' read -r tipo originale pezzo <<< "$riga"
        [ "$tipo" = spostata ] || continue
        [ -z "${fatta[$pezzo]:-}" ] || continue
        echo "  $pezzo -> $DESTINAZIONE/$originale"
        rc mkdir "$DESTINAZIONE/$originale"
        rc copy "$pezzo" "$DESTINAZIONE/$originale" --ignore-existing --create-empty-src-dirs \
            --transfers 1 --checkers 1 \
            || errore "la copia di $pezzo in $DESTINAZIONE/$originale non è finita: rilancia $0 sistema"
        e=0
        rc check "$pezzo" "$DESTINAZIONE/$originale" --one-way --size-only \
            --missing-on-dst "$TMPD/mancano" --differ "$TMPD/diversi" 2> "$TMPD/controllo" || e=$?
        if [ -s "$TMPD/mancano" ] || { [ "$e" -ne 0 ] && [ ! -s "$TMPD/diversi" ]; }; then
            cat "$TMPD/controllo" >&2
            errore "in $DESTINAZIONE/$originale manca qualcosa di $pezzo: rilancia $0 sistema"
        fi
        if [ -s "$TMPD/diversi" ]; then
            echo "    $(wc -l < "$TMPD/diversi") file con lo stesso nome ma un'altra dimensione: restano solo in $pezzo"
            sed "s|^|$pezzo/|" "$TMPD/diversi" >> "$REGISTRO.diversi"
        fi
        printf 'ricopiata\t%s\t%s\n' "$originale" "$pezzo" >> "$REGISTRO"
        fatta[$pezzo]=1
    done
}

# Le cartelle (relative a DESTINAZIONE) ricopiate: una sola copia, e niente doppie dentro.
controlla() {
    local originale n tutto_bene=si
    for originale in "$@"; do
        n="$(copie_di "$originale")"
        if [ "$n" -ne 1 ] || [ -n "$(elenca_doppie "$DESTINAZIONE/$originale")" ]; then
            echo "  NO  $originale: $n copie, o cartelle doppie dentro"
            tutto_bene=no
        else
            echo "  ok  $originale: una sola"
        fi
    done
    [ "$tutto_bene" = si ] || errore "restano cartelle doppie: guarda sopra"
}

in_sospeso() {
    awk -F'\t' '$1 == "spostata" { s[$3] = $2; o[++n] = $3 } $1 == "ricopiata" { delete s[$3] }
        END { for (i = 1; i <= n; i++) if (o[i] in s) print s[o[i]] "\t" o[i] }' "$REGISTRO"
}

case "$FASE" in
    elenca)
        echo "Cerco le cartelle doppie in $DESTINAZIONE…"
        doppie="$(elenca_doppie "$DESTINAZIONE")"
        if [ -n "$doppie" ]; then
            printf '%s\n' "$doppie" | awk -F'\t' '{ printf "  %s: %d copie\n", $1, $2 }'
            echo "Per sistemarle: $0 sistema"
        else
            echo "Nessuna cartella doppia."
        fi
        sospese="$(in_sospeso)"
        if [ -n "$sospese" ]; then
            echo "Copie spostate in $DOPPIE e non ancora ricopiate (le ricopia $0 sistema):"
            printf '%s\n' "$sospese" | awk -F'\t' '{ printf "  %s <- %s\n", $1, $2 }'
        fi
        ;;
    sistema)
        if [ $# -gt 0 ]; then
            doppie=""
            for p in "$@"; do
                p="${p#/}"; p="${p%/}"
                [ -n "$p" ] || errore "dammi i percorsi dentro $DESTINAZIONE, come 2015/11"
                n="$(copie_di "$p")"
                if [ "$n" -lt 2 ]; then
                    echo "$p: $([ "$n" -eq 1 ] && echo "una sola copia" || echo "non c'è"), niente da fare"
                else
                    doppie+="$p"$'\t'"$n"$'\n'
                fi
            done
        else
            echo "Cerco le cartelle doppie in $DESTINAZIONE…"
            doppie="$(elenca_doppie "$DESTINAZIONE")"
        fi
        rifatte=()
        while IFS=$'\t' read -r p n; do
            [ -n "$p" ] || continue
            echo "$p: $n copie, le sposto in $DOPPIE"
            separa "$DESTINAZIONE/$p" "$p"
            rifatte+=("$p")
        done <<< "$doppie"
        # Anche quelle spostate da un giro interrotto prima di ricopiarle.
        while IFS=$'\t' read -r p _; do
            [ -n "$p" ] || continue
            rifatte+=("$p")
        done < <(in_sospeso)
        if [ "${#rifatte[@]}" -eq 0 ]; then
            echo "Nessuna cartella doppia, niente da ricopiare."
            exit 0
        fi
        echo "Ricopio in $DESTINAZIONE, un file alla volta…"
        ricopia
        echo "Controllo…"
        mapfile -t rifatte < <(printf '%s\n' "${rifatte[@]}" | LC_ALL=C sort -u)
        controlla "${rifatte[@]}"
        if [ -s "$REGISTRO.diversi" ]; then echo "File rimasti solo in $DOPPIE (stesso nome, altra dimensione): $REGISTRO.diversi"; fi
        echo "Fatto. Le copie spostate restano in $DOPPIE: guardale e buttale a mano quando"
        echo "sei sicuro (rclone lsf -R $DOPPIE). Poi rilancia carica."
        ;;
    *) errore "fase sconosciuta: $FASE (elenca, sistema)" ;;
esac
