# IAC33 private LLM server

This directory defines the production deployment target for a private GPU-backed Ollama server on Lambda Cloud.

Target GPU: 1x H100 80GB. Default model: gpt-oss:20b. Public traffic is terminated by Caddy and authenticated before proxying to Ollama on localhost:11434.
