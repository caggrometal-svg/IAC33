#!/usr/bin/env bash
set -euo pipefail

MODEL="${IAC33_OLLAMA_MODEL:-gpt-oss:20b}"
API_KEY="${IAC33_OLLAMA_API_KEY:?IAC33_OLLAMA_API_KEY is required}"
PUBLIC_IP="$(curl -fsS --max-time 10 https://api.ipify.org)"
PUBLIC_HOST="${PUBLIC_IP}.sslip.io"

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg ufw

# Ollama: official installer. Lambda's Ubuntu image provides the NVIDIA driver/CUDA stack.
curl -fsSL https://ollama.com/install.sh | sh

# Keep the model resident and avoid exposing Ollama itself to the network.
install -d -m 0755 /etc/ollama
cat >/etc/ollama/serve.conf <<EOF
OLLAMA_HOST=127.0.0.1:11434
OLLAMA_KEEP_ALIVE=-1
OLLAMA_NUM_PARALLEL=2
OLLAMA_MAX_LOADED_MODELS=1
OLLAMA_MAX_QUEUE=64
OLLAMA_NO_CLOUD=1
EOF

install -d -m 0755 /etc/systemd/system/ollama.service.d
cat >/etc/systemd/system/ollama.service.d/10-iac33.conf <<'EOF'
[Service]
EnvironmentFile=/etc/ollama/serve.conf
EOF

systemctl daemon-reload
systemctl enable --now ollama

# Wait for the service before loading the model.
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1:11434/api/tags >/dev/null; then break; fi
  sleep 2
done

ollama pull "$MODEL"

# Public TLS/auth gateway. The Lambda cloud firewall must allow TCP/443.
apt-get install -y --no-install-recommends debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg
chmod o+r /etc/apt/sources.list.d/caddy-stable.list
apt-get update
apt-get install -y --no-install-recommends caddy

cat >/etc/caddy/Caddyfile <<EOF
https://${PUBLIC_HOST} {
    @authorized header Authorization "Bearer ${API_KEY}"
    handle @authorized {
        reverse_proxy 127.0.0.1:11434
    }
    handle {
        respond "unauthorized" 401
    }
}
EOF

caddy validate --config /etc/caddy/Caddyfile
systemctl enable --now caddy

# Host-level firewall. Provider firewall must also permit 443.
ufw allow 22/tcp
ufw allow 443/tcp
ufw --force enable

# Small local health proof, without printing the secret.
curl -fsS --max-time 10 http://127.0.0.1:11434/api/tags >/dev/null

cat >/etc/iac33-llm.env <<EOF
IAC33_OLLAMA_ENDPOINT=https://${PUBLIC_HOST}
IAC33_OLLAMA_MODEL=${MODEL}
IAC33_OLLAMA_API_KEY=${API_KEY}
EOF
chmod 600 /etc/iac33-llm.env

echo "IAC33_OLLAMA_ENDPOINT=https://${PUBLIC_HOST}"
echo "IAC33_OLLAMA_MODEL=${MODEL}"
echo "IAC33_LLM_STATUS=ready"
