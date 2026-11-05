#!/usr/bin/env bash
# Egyszeri futtatás: aláíró kulcs készítése, majd a GitHub secretek értékeinek kiírása.
# Használat:  bash scripts/make-keystore.sh
set -euo pipefail

ALIAS="${NOVA_KEY_ALIAS:-nova}"
read -rsp "Keystore jelszó: " PASS; echo

keytool -genkeypair -v \
  -keystore nova.jks -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=NovaProcess, OU=Astro, O=Nova, L=Gyor, C=HU"

echo
echo "GitHub → Settings → Secrets and variables → Actions → New repository secret:"
echo "  NOVA_KEYSTORE_BASE64  = (alábbi egy sor)"
base64 -w0 nova.jks 2>/dev/null || base64 nova.jks | tr -d '\n'
echo
echo "  NOVA_KEYSTORE_PASSWORD = $PASS"
echo "  NOVA_KEY_ALIAS         = $ALIAS"
echo "  NOVA_KEY_PASSWORD      = $PASS"
echo
echo "A nova.jks fájlt NE commitold (a .gitignore kizárja); tedd el biztonságos helyre."
