#!/bin/sh
# Deletes the eMudhra emSign roots and Comodo AAA Certificate Services from a
# Java trust store (CVE-2026-114820, https://ops.java/emudhra). Entries are
# matched by SHA-256, since every store names them differently. Exits non-zero
# if the store cannot be read, if any of them remains afterwards, or if any
# other entry changed.
#
# Usage: remove-distrusted-roots.sh <store> <storepass>
#
# The same file ships in several images (hopsworks-ee payara-server,
# payara-node, payara-micro; trino; docker-images opensearch); keep the copies
# identical. POSIX sh with keytool and shell builtins only: the Trino base has
# no sed or awk, and payara-micro is Alpine.
set -eu

if [ $# -ne 2 ]; then
  echo "Usage: $0 <store> <storepass>" >&2
  exit 2
fi
store=$1
storepass=$2

keytool=keytool
if [ -n "${JAVA_HOME:-}" ]; then
  keytool=$JAVA_HOME/bin/keytool
fi

TAB=$(printf '\t')
NL='
'

die() {
  echo "$0: $store: $*" >&2
  exit 1
}

is_distrusted() {
  case "$1" in
    40:F6:AF:03:46:A9:9A:A1:CD:1D:55:5A:4E:9C:CE:62:C7:F9:63:46:03:EE:40:66:15:83:3D:C8:C8:D0:03:67) ;; # emSign Root CA - G1
    1A:A0:C2:70:9E:83:1B:D6:E3:B5:12:9A:00:BA:41:F7:EE:EF:02:08:72:F1:E6:50:4B:F0:F6:C3:F2:4F:3A:F3) ;; # emSign Root CA - G2
    86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B) ;; # emSign ECC Root CA - G3
    12:56:09:AA:30:1D:A0:A2:49:B9:7A:82:39:CB:6A:34:21:6F:44:DC:AC:9F:39:54:B1:42:92:F2:E8:C8:60:8F) ;; # emSign Root CA - C1
    BC:4D:80:9B:15:18:9D:78:DB:3E:1D:8C:F4:F9:72:6A:79:5D:A1:64:3C:A5:F1:35:8E:1D:DB:0E:DC:0D:7E:B3) ;; # emSign ECC Root CA - C3
    D7:A7:A0:FB:5D:7E:27:31:D7:71:E9:48:4E:BC:DE:F7:1D:5F:0C:3E:0A:29:48:78:2B:C8:3E:E0:EA:69:9E:F4) ;; # AAA Certificate Services
    *) return 1 ;;
  esac
}

record() {
  if [ -z "$alias" ]; then
    return 0
  fi
  if [ -z "$fingerprint" ]; then
    die "no SHA-256 fingerprint listed for alias '$alias'"
  fi
  pairs=$pairs$alias$TAB$fingerprint$NL
  count=$((count + 1))
  alias=
}

# Sets pairs to one "<alias><TAB><SHA-256>" line per entry and count to their
# number. For an entry with a certificate chain, the first certificate's
# fingerprint is taken.
list() {
  # English labels, so the patterns below match whatever the image locale is.
  if ! out=$("$keytool" -J-Duser.language=en -list -v -keystore "$store" -storepass "$storepass" </dev/null 2>&1); then
    die "cannot list: $out"
  fi
  pairs=
  count=0
  expected=
  alias=
  fingerprint=
  while IFS= read -r line; do
    case "$line" in
      "Your keystore contains "*)
        expected=${line#Your keystore contains }
        expected=${expected%% *}
        ;;
      "Alias name: "*)
        record
        alias=${line#Alias name: }
        fingerprint=
        ;;
      "$TAB SHA256: "*)
        if [ -z "$fingerprint" ]; then
          fingerprint=${line#"$TAB SHA256: "}
        fi
        ;;
    esac
  done <<EOF
$out
EOF
  record
  if [ "$count" != "$expected" ]; then
    die "parsed $count entries, keytool reports '$expected'"
  fi
}

contains() {
  case "$NL$1" in
    *"$NL$2$NL"*) return 0 ;;
    *) return 1 ;;
  esac
}

if [ ! -f "$store" ] || [ ! -r "$store" ]; then
  die "not a readable file"
fi

list
kept=
kept_count=0
removed=0
while IFS="$TAB" read -r a f; do
  if [ -z "$a" ]; then
    continue
  fi
  if is_distrusted "$f"; then
    if ! out=$("$keytool" -J-Duser.language=en -delete -alias "$a" -keystore "$store" -storepass "$storepass" </dev/null 2>&1); then
      die "cannot delete '$a': $out"
    fi
    echo "$store: removed '$a' ($f)"
    removed=$((removed + 1))
  else
    kept=$kept$a$TAB$f$NL
    kept_count=$((kept_count + 1))
  fi
done <<EOF
$pairs
EOF

list
while IFS="$TAB" read -r a f; do
  if [ -z "$a" ]; then
    continue
  fi
  if is_distrusted "$f"; then
    die "'$a' ($f) is still present"
  fi
  if ! contains "$kept" "$a$TAB$f"; then
    die "unexpected entry '$a' ($f) after the deletions"
  fi
done <<EOF
$pairs
EOF
if [ "$count" -ne "$kept_count" ]; then
  die "$count entries after the deletions, expected $kept_count"
fi
echo "$store: $removed distrusted entries removed, $kept_count kept"
