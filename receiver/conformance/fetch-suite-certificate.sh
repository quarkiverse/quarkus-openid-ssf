#!/usr/bin/env bash
# Saves the TLS certificate of the locally running conformance suite as certs/conformance-suite.pem,
# so that the receiver can trust it (quarkus.tls.conformance-suite.trust-store in application.properties).
set -euo pipefail
cd "$(dirname "$0")"
host="${1:-localhost.emobix.co.uk:8443}"
mkdir -p certs
openssl s_client -connect "$host" -servername "${host%%:*}" </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > certs/conformance-suite.pem
openssl x509 -in certs/conformance-suite.pem -noout -subject -dates
echo "Saved to certs/conformance-suite.pem"
