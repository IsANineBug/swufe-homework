#!/usr/bin/env bash
# 生成自签证书（EC P-256，10 年），并输出 App 固定用的公钥 pin。
# 用法：./scripts/gen-cert.sh <服务器公网IP>
# 公钥固定：续期时复用同一把私钥（加 --renew），pin 不变，旧版 App 不受影响。
set -euo pipefail

IP="${1:?用法: $0 <服务器公网IP> [--renew]}"
RENEW="${2:-}"
DIR="$(cd "$(dirname "$0")/.." && pwd)/certs"
mkdir -p "$DIR"
umask 077

if [[ "$RENEW" != "--renew" || ! -f "$DIR/server.key" ]]; then
  if [[ -f "$DIR/server.key" ]]; then
    echo "已存在 $DIR/server.key。换私钥会改变 pin，旧版 App 将无法连接。" >&2
    echo "续期请用：$0 $IP --renew" >&2
    exit 1
  fi
  openssl ecparam -name prime256v1 -genkey -noout -out "$DIR/server.key"
fi

openssl req -new -x509 -days 3650 -key "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/CN=swufe-homework" \
  -addext "subjectAltName=IP:${IP}" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature" \
  -addext "extendedKeyUsage=serverAuth"
chmod 644 "$DIR/server.crt"

PIN=$(openssl x509 -in "$DIR/server.crt" -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64)

echo
echo "证书：$DIR/server.crt（私钥 server.key 不要提交、不要外传）"
echo "公钥 pin（OkHttp CertificatePinner 格式）："
echo "  sha256/${PIN}"
echo
echo "填到：GitHub Secrets 的 SERVER_CERT_PIN，以及电脑端 worker/.env 的 SERVER_CERT_PIN"
