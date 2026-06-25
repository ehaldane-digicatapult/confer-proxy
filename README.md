# Confer Proxy

A TEE-resident Java proxy that forms the encrypted gateway between client browsers
and a private LLM inference stack. This repository is a fork of
[ConferLabs/confer-proxy](https://github.com/ConferLabs/confer-proxy), the component
that powers the [confer.to](https://confer.to) private AI service.

The proxy runs exclusively inside a confidential VM (Intel TDX or AMD SEV-SNP). It
will not start outside a TEE; the absence of a recognised attestation device is a
hard startup failure, not a graceful fallback.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Network Traffic Boundaries](#network-traffic-boundaries)
- [Attestation](#attestation)
- [Security Boundaries](#security-boundaries)
- [Configuration Reference](#configuration-reference)
- [Operator Runbook](#operator-runbook)
- [Development](#development)
- [Related Repositories](#related-repositories)

---

## Overview

Confer's core promise is that neither the infrastructure host nor any network
intermediary can observe a user's conversation. The proxy enforces this by:

1. Accepting all client traffic over a **Noise protocol encrypted WebSocket**. The
   encryption layer sits below TLS, so even a compromised or curious TLS terminator
   sees only opaque bytes.
2. Running inside a **hardware-isolated TEE** whose memory is encrypted and
   integrity-protected by the CPU. The host OS, hypervisor, and cloud provider
   cannot read or modify the enclave's working memory.
3. Binding its **Noise public key to the TEE attestation report** at startup, so
   clients can verify, before sending any plaintext, that they are connected to the
   correct, unmodified code running in a genuine enclave.
4. Keeping all inference traffic on **localhost** inside the enclave. vLLM, the
   document extraction service, and the embedding model never communicate outside the
   TEE boundary.

---

## Architecture

```
  ┌───────────────────────────────────────────────────────────────────────┐
  │  Client (browser)                                                     │
  │                                                                       │
  │  • Noise XX handshake → verifies attestation + Noise public key       │
  │  • All subsequent traffic: Noise-encrypted protobuf over WebSocket    │
  └───────────────────────┬───────────────────────────────────────────────┘
                          │  WebSocket :443 / :8080
                          │  (Noise_XX_25519_AESGCM_SHA256)
  ┌───────────────────────▼───────────────────────────────────────────────┐
  │  TEE BOUNDARY  (Intel TDX or AMD SEV-SNP encrypted VM)                │
  │  dm-verity root filesystem, measures disk modification                │
  │                                                                       │
  │  ┌─────────────────────────────────────────────────────────────────┐  │
  │  │  confer-proxy  (this repo)       :8080 public / :9090 localhost │  │
  │  │                                                                 │  │
  │  │  • Noise handshake + attestation response                       │  │
  │  │  • JWT auth (WebSocket upgrade)                                 │  │
  │  │  • Route: POST /v1/vllm/chat/completions                        │  │
  │  │           POST /v1/embeddings                                   │  │
  │  │           POST /v1/document/extract                             │  │
  │  │           POST /v1/fetch/html                                   │  │
  │  │           GET  /v1/ping  (liveness + attestation health)        │  │
  │  │           GET  /v1/images (AES-GCM encrypted S3 objects)        │  │
  │  │                                                                 │  │
  │  │  • Embedding model: Snowflake Arctic Embed M v2.0 (in-process)  │  │
  │  └──────┬───────────────────────────────────────────────┬──────────┘  │
  │         │ HTTP localhost:8000                           │             │
  │  ┌──────▼───────────────────┐                ┌──────────▼──────────┐  │
  │  │  vLLM (inference engine) │                │  docling-serve      │  │
  │  │  OpenAI-compatible API   │                │  document OCR/parse │  │
  │  │  localhost:8000          │                │  localhost:5001     │  │
  │  └──────────────────────────┘                └─────────────────────┘  │
  └────────────────────────────────────┬──────────────────────────────────┘
                                       │  Outbound (TEE NIC)
             ┌─────────────────────────┼─────────────────────────────┐
             │                         │                             │
  ┌──────────▼──────────┐   ┌──────────▼──────────┐   ┌──────────────▼────┐
  │  Intel Trust        │   │  Tavily API         │   │  AWS S3           │
  │  Authority (TDX)    │   │  web search/extract │   │  encrypted images │
  │  api.trustauthority │   │  api.tavily.com     │   │  (client-keyed    │
  │  .intel.com         │   │                     │   │   AES-256-GCM)    │
  └─────────────────────┘   └─────────────────────┘   └───────────────────┘
                                       │
                            ┌──────────▼──────────┐
                            │  proxy.corsfix.com  │
                            │  HTML fetch proxy   │
                            │  (privacy relay)    │
                            └─────────────────────┘
```

The **confer-image** repository (see [Related Repositories](#related-repositories))
builds the OS image that the TEE boots: Ubuntu Noble base, NVIDIA drivers, vLLM,
the Java runtime for this proxy, and the dm-verity configuration that binds the
entire root filesystem to the attestation measurements.

---

## Network Traffic Boundaries

### Ports exposed by the proxy

| Port | Interface | Purpose |
|------|-----------|---------|
| 8080 | All interfaces | WebSocket endpoint (`/websocket`); all client traffic enters here |
| 9090 | Localhost only | MicroProfile metrics (`/metrics`); never reachable from outside the VM |

### Loopback-only traffic (never leaves the TEE)

| Destination | Purpose |
|-------------|---------|
| `localhost:8000` | vLLM OpenAI-compatible API - chat completions |
| `localhost:5001` | docling-serve - document OCR and structure extraction (optional, disabled by default) |
| In-process ONNX | Snowflake Arctic Embed M v2.0 - text embedding inference |

All model inference traffic stays inside the enclave. The host OS and hypervisor
never see the plaintext content of any inference request or response.

### Outbound connections from the TEE

| Destination | Protocol | Purpose | Carries user content? |
|-------------|----------|---------|----------------------|
| `api.trustauthority.intel.com` | HTTPS | TDX quote verification: Submits the raw TDX quote, receives a signed JWT | No - only the Noise public key hash |
| `api.tavily.com` | HTTPS | Web search and page extraction for tool calls | Yes - search queries from the model |
| `proxy.corsfix.com` | HTTPS | HTML page fetch relay; prevents Confer's network from seeing target URLs directly | Yes - fetched URLs |
| AWS S3 | HTTPS (AWS SDK) | Retrieval of client-uploaded encrypted images | No - ciphertext only |
| Logtail / Better Stack | HTTPS | Structured log ingestion | Operator-configured; no conversation content |

The Corsfix relay deserves specific mention: it provides a layer of network privacy
so that neither Confer's egress IP nor the user's intended target URL appear together
in any single network log. The proxy never contacts target URLs directly on behalf
of the user.

---

## Attestation

Attestation is how a client verifies, before sending any plaintext, that it is
communicating with the intended code running in a genuine, unmodified TEE. The proxy
supports two hardware paths.

### Noise key binding

At startup, `NoiseKeyProducer` generates a fresh ephemeral X25519 key pair. Before
requesting an attestation quote, `AttestationService` places the raw 32-byte public
key in the first 32 bytes of the 64-byte TSM `report_data` / `inblob` field. The
resulting hardware quote cryptographically commits to this specific key, so:

- A client can extract the public key from the attestation quote.
- After completing the Noise handshake, the client knows the key it negotiated with
  is the same key the TEE attested to.
- A man-in-the-middle cannot substitute a different public key without invalidating
  the quote.

### Intel TDX path

1. The proxy writes the 64-byte `report_data` to
   `/sys/kernel/config/tsm/report/entryNNN/inblob` and reads the resulting TDX quote
   from `outblob`.
2. The raw quote is base64-encoded and POSTed to Intel Trust Authority (ITA) at
   `https://api.trustauthority.intel.com/appraisal/v1/attest`.
3. ITA returns a signed JWT whose claims include the TDX measurements (MRTD,
   RTMR[0–3]). The proxy caches this JWT and refreshes it 60 seconds before expiry.
4. The JWT, together with the proxy's Noise public key, the `manifest.json`, and the
   Sigstore bundle, is sent to the client as JSON payload inside the Noise handshake
   message.

Platform detected via `/dev/tdx_guest`.

### AMD SEV-SNP path

1. The proxy writes the same `report_data` to the TSM sysfs interface and reads the
   raw SEV-SNP attestation report from `outblob`.
2. The report is base64-encoded. No third-party verification call is made; the raw
   hardware report is returned directly.
3. The report, Noise public key, `manifest.json`, and Sigstore bundle are sent in the
   Noise handshake payload.

Platform detected via `/dev/sev-guest`. TDX is checked first; SEV-SNP is the fallback.

### Supply chain: manifest signing via Sigstore

The attestation report proves the TEE is genuine, but a client also needs to know
that the _code_ running inside it is the intended version. This is handled at build
time:

1. The CI pipeline downloads the image artefacts (vmlinuz, initrd) from S3 and
   computes the SHA-256 hash of the proxy zip.
2. The proxy hash is appended to the kernel command line as
   `proxy-hash=sha256:<hex>`. Because the kernel command line is measured by TDX
   during direct-boot, the proxy binary is included in the hardware measurements.
3. `tdx-measure` computes the expected TDX measurements (MRTD and RTMR[0–3]) for
   the exact (kernel + initrd + command line) tuple.
4. A `manifest.json` is created containing `imageVersion`, `proxyVersion`, and the
   computed `tdxMeasurements`.
5. The manifest is signed with `cosign sign-blob` using a GCP service account
   identity. The signature and certificate are submitted to the Sigstore Rekor
   transparency log, producing a `manifest.bundle.json`.
6. Both files are uploaded to S3 and, at runtime, are placed at
   `/run/confer/manifest.json` and `/run/confer/manifest.bundle.json` inside the
   enclave. They are included verbatim in every attestation response.

Production releases are signed against the Sigstore production Rekor instance.
Pre-release (`snapshot`) builds use the Sigstore staging instance.

### Client verification flow

A conforming client performs the following steps during the Noise handshake:

1. Complete the Noise XX handshake as initiator.
2. Extract the `AttestationResponse` JSON payload embedded in the handshake message.
3. Verify the Sigstore bundle against the Rekor transparency log to confirm the
   manifest was signed by the expected identity.
4. Check that the TDX measurements in `manifest.json` match the measurements
   reported in the ITA JWT (TDX) or SEV-SNP report.
5. Confirm that the Noise public key in the attestation `report_data` matches the
   key negotiated in step 1.
6. Only after all checks pass, consider the channel trusted and send application
   data.

### Attestation health endpoint

`GET /v1/ping` (plain HTTP, not WebSocket) calls
`attestationService.getSignedAttestation()`. It returns `200 PONG` if attestation is
healthy, or `503` if it fails. This can be used as a liveness/readiness probe by the
enclave's process supervisor.

---

## Security Boundaries

### TEE hardware isolation

All code in this repository runs inside a Confidential VM. The CPU enforces that the
VM's memory is encrypted with keys that the host OS and hypervisor cannot access.
Attempting to inspect or modify the enclave's memory from outside produces only
ciphertext, and integrity violations trigger a hardware fault.

The proxy fails to start if neither `/dev/tdx_guest` nor `/dev/sev-guest` is present
(`AttestationServiceProducer` throws `IllegalStateException`). There is no
unattested fallback mode.

### dm-verity filesystem integrity

The root filesystem of the enclave image is protected by dm-verity. The verity root
hash is embedded in the kernel command line, which is measured into the TEE during
boot. Any modification to the disk image, including the proxy JAR, vLLM, or any
system binary, produces a different root hash, which changes the kernel command line,
which changes the TDX measurements, which invalidates the attestation. Clients
checking measurements against the signed manifest will reject the connection.

### Noise end-to-end encryption

Client traffic is encrypted with the Noise XX protocol
(`Noise_XX_25519_AESGCM_SHA256`) before it reaches the WebSocket layer. This means:

- The encryption is established between the client and the proxy inside the TEE, not
  at a TLS terminator that the infrastructure host controls.
- Even a full TLS inspection proxy or a compromised load balancer sees only opaque
  Noise-encrypted frames.
- The handshake provides mutual authentication: the client authenticates the TEE via
  attestation; the TEE authenticates the client via the JWT checked before the
  WebSocket upgrade (`WebsocketAuthenticator`).

Noise messages are framed as protobuf `NoiseTransportFrame` chunks (max ~65 KB each)
to respect Noise's 65535-byte payload limit. Multi-chunk messages are reassembled by
`NoiseTransportFramer` before being handed to application logic.

### JWT authentication

WebSocket connections require a `?token=<jwt>` query parameter. The JWT must be
signed with HMAC-SHA256 by the `"kerf"` issuer using the configured `jwt.secret`.
The `WebsocketAuthenticator` checks this before the upgrade handshake completes;
unauthenticated upgrade requests are rejected immediately. Free-tier users receive
`402 Payment Required` once their token expiry is reached; subscribed users are not
subject to per-request token expiry.

### Encrypted image access

Images uploaded by clients are stored in S3 encrypted with AES-256-GCM via
`ChunkedCipher`. The encryption key is held by the client and provided in the request
(`?ek=`); the proxy never stores it. S3 access via `ImageController` additionally
requires a short-lived server-internal `imageToken`, generated per session, to
prevent the model from crafting SSRF-style requests to the image endpoint.

The `ChunkedCipher` format chains the GCM authentication tag of each chunk into the
AAD of the next, detecting reordering, truncation, and cross-file substitution attacks
in addition to standard AES-GCM tamper detection.

### CORS

`CorsFilter` enforces an origin allow-list (configurable via `cors.allow-origins`).
The default allow-list includes `https://confer.to` and local development origins.

---

## Configuration Reference

Configuration is read from `microprofile-config.properties` and can be overridden via
environment variables or system properties (standard MicroProfile Config precedence).

### Required secrets

| Property | Environment variable | Description |
|----------|---------------------|-------------|
| `ita.api_key` | `ITA_API_KEY` | Intel Trust Authority API key (TDX deployments) |
| `jwt.secret` | `JWT_SECRET` | HMAC-SHA256 signing secret for client JWTs |
| `tavily_api_key` | `TAVILY_API_KEY` | Tavily API key for web search and page extraction |
| `s3.bucket` | `S3_BUCKET` | S3 bucket name for encrypted image storage |
| `AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | AWS credentials for S3 access |
| `AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | AWS credentials for S3 access |
| `LOGGING_SOURCE_TOKEN` | `LOGGING_SOURCE_TOKEN` | Better Stack / Logtail source token |
| `LOGGING_INGEST_URL` | `LOGGING_INGEST_URL` | Better Stack / Logtail ingest URL |

### Runtime files (placed by the enclave boot process)

| Path | Description |
|------|-------------|
| `/run/confer/manifest.json` | Signed manifest: image version, proxy version, TDX measurements |
| `/run/confer/manifest.bundle.json` | Sigstore bundle: Rekor log entry and certificate for manifest signature |

Both files are served verbatim in every attestation response. The proxy starts
without them (graceful degradation) but attestation responses will lack supply-chain
provenance; clients performing full verification will reject the connection.

### Key tunables

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | Public WebSocket port |
| `ita.url` | `https://api.trustauthority.intel.com` | Intel Trust Authority base URL |
| `cors.allow-origins` | `https://confer.to,...` | Comma-separated CORS origin allow-list |
| `s3.region` | `us-east-1` | AWS region for S3 client |
| `vllm.served.model.name` | _(required)_ | Model name as served by vLLM |
| `vllm.max.model.len` | `262144` | Maximum context length in tokens |
| `max_tool_iterations` | `10` | Maximum tool-call loop iterations per request |
| `docling.enabled` | `false` | Enable document extraction via docling-serve |
| `docling.port` | `5001` | Port for local docling-serve |
| `tavily.search_depth` | `basic` | Tavily search depth (`basic` or `advanced`) |
| `tavily.extract_depth` | `advanced` | Tavily extract depth (`basic` or `advanced`) |
| `corsfix_api_key` | _(optional)_ | API key for proxy.corsfix.com HTML fetch relay |

---

## Operator Runbook

### Prerequisites

- JDK 25
- Maven 3.9+
- The proxy must be deployed inside an Intel TDX or AMD SEV-SNP Confidential VM
- vLLM running at `localhost:8000` with a model name matching `vllm.served.model.name`

### Building

```bash
# Compile, run tests, and produce the proxy JAR
mvn package

# Package the proxy zip (proxy.jar + libs/) and upload to S3
# Requires S3_BUCKET, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY in environment
mvn package -P ship
```

The `ship` profile uploads to `s3://$S3_BUCKET/proxy/<version>/proxy.zip`.

### CI/CD pipeline

The `.github/workflows/release.yaml` pipeline handles the full supply chain:

1. **Determine versions**: reads `pom.xml` (proxy version) and `image-version` file.
2. **Download image artefacts**: fetches the built vmlinuz, initrd, and cmdline from
   S3.
3. **Compute proxy hash**: SHA-256 of the proxy zip; appended to the kernel cmdline
   as `proxy-hash=sha256:<hex>`.
4. **Measure TDX**: runs `tdx-measure --runtime-only --direct-boot=true` over the
   kernel + initrd + full cmdline to produce expected MRTD and RTMR[0–3] values.
5. **Create manifest**: writes `manifest.json` with image version, proxy version, and
   TDX measurements.
6. **Sign manifest**: `cosign sign-blob` using a GCP service account identity;
   submits to Sigstore Rekor.
7. **Upload artefacts**: manifest, bundle, and updated cmdline go to S3.

Versions containing the string `snapshot` use the Sigstore **staging** Rekor
instance. All other versions use Sigstore **production**.

### Running the proxy

The proxy is packaged as a distroless container image (`jib-maven-plugin` targets
`gcr.io/distroless/java-base-debian12:nonroot`, linux/amd64). It can also be run
directly:

```bash
java -jar proxy.jar
```

Ensure the runtime files (`/run/confer/manifest.json`,
`/run/confer/manifest.bundle.json`) are written before starting the proxy. In the
confer-image boot sequence these are placed by the enclave initialisation scripts
after the image is verified.

### Liveness probe

```
GET http://localhost:8080/v1/ping
```

Returns `200` with body `PONG` when the proxy is healthy and attestation is
operational. Returns `503` if attestation fails. Use this as a readiness gate before
routing client traffic to the enclave.

### Logging

The proxy logs structured JSON via Logback to stdout and to Better Stack (Logtail)
when `LOGGING_SOURCE_TOKEN` and `LOGGING_INGEST_URL` are set. On startup,
`AttestationLogger` emits the TDX MRTD and RTMR[0–3] values (or SEV-SNP launch
measurement) at INFO level for operator audit.

---

## Development

### Running tests

Tests are platform agnostic and not conditional on TEEs or silicon; macOS, Linux, and
Windows all work. However, Internet access is required to download a placeholder ONNX
model as part of the `EmbeddingServiceTest` integration test.

```bash
mvn test
```

Tests use a random port (`server.port=-1`) to avoid conflicts. The test suite covers:

- `ChunkedCipher` - cross-platform AES-GCM test vectors including tamper, reorder,
  truncation, and cross-file substitution detection
- `NoiseConnectionWebsocket` - concurrent send correctness (10 threads × 100
  messages), large message fragmentation
- `WebsocketController` - request routing, virtual thread dispatch, free-tier token
  expiry, streaming response pipeline, error handling
- `StreamRegistry` / `StreamContext` - chunked upload reassembly, back-pressure
  limits, cancellation

### Evaluating a host system

If a developer wants to check whether they are running on TEE-capable hardware,
they can run any of the following in either a guest or host:

- Check for TDX:
`ls /dev/tdx_guest 2>/dev/null && echo "TDX guest: present"`

- Check for SEV-SNP: `ls /dev/sev-guest 2>/dev/null && echo "SEV-SNP guest: present"`

- Check CPU flags for TDX support: `grep -m1 "^flags" /proc/cpuinfo | tr ' ' '\n' | grep -E "^(tdx|sev|sev_snp)$"`

- Check kernel TSM sysfs interface: `ls /sys/kernel/config/tsm/report/ 2>/dev/null && echo "TSM sysfs: present"`

### Protobuf

Wire message schemas are defined in `proto/noise_transport.proto`. Java sources are
generated automatically by `protobuf-maven-plugin` during `mvn generate-sources`. Do
not edit the generated files in `target/generated-sources/` directly.
