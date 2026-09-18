# IAC33 private LLM server

## Target

- Provider: Lambda Cloud On-Demand GPU
- Image: Lambda Stack 22.04 (Ubuntu-based, NVIDIA driver/CUDA stack supplied by the provider)
- GPU target: 1x H100 80GB PCIe
- Model: Ollama + `gpt-oss:20b`
- API: OpenAI-compatible `/v1/chat/completions`
- Public TLS: Caddy
- Authentication: a dedicated `X-IAC33-LLM-Key` header
- Ollama itself stays on `127.0.0.1:11434`

Lambda's default ODC image is Ubuntu 22.04 LTS and includes the NVIDIA driver, CUDA, cuDNN, NCCL and related AI tooling. The 20B gpt-oss model is about 14 GB and is intended for lower-latency use; it is open-weight under Apache 2.0.

## Provisioning

Run the GitHub Actions workflow `IAC33 Private LLM Server` after configuring these repository secrets:

- `LAMBDA_API_KEY`
- `LAMBDA_SSH_KEY_NAME` (an SSH public key already registered in Lambda)
- `LAMBDA_FIREWALL_RULESET_ID` (regional ruleset permitting TCP/443)
- `IAC33_OLLAMA_API_KEY` (long random application token)

The workflow checks live GPU capacity, selects an available region, launches the instance with cloud-init, installs Ollama/Caddy, downloads the model and reports the resulting HTTPS endpoint.

## Network security

Lambda's default firewall only permits SSH on TCP/22. The attached ruleset must permit TCP/443. The host UFW configuration also permits only TCP/22 and TCP/443 for inbound traffic.

Do not expose port 11434 directly.

## Durability

Lambda On-Demand instances are terminated rather than suspended; local NVMe data does not survive termination. The model is therefore reproducible by the bootstrap script and should be treated as a cache, not the source of truth.

## IAC33 integration

After provisioning, set these Render environment variables on `iac33-backend`:

- `AI_PROVIDER_ORDER=ollama,kilo,horde,pollinations`
- `IAC33_OLLAMA_ENDPOINT=https://<public-ip>.sslip.io`
- `IAC33_OLLAMA_MODEL=gpt-oss:20b`
- `IAC33_OLLAMA_API_KEY=<same application token>`

The Android app continues to call the existing IAC33 backend. No provider API key is shipped in the APK.

## Operational limitation

The model can be used without per-message/token charges from an inference provider, but the GPU instance itself is billable while running. Current Lambda published pricing lists H100 instances in the multi-dollar/hour range, so 24/7 operation is not a zero-cost service.
