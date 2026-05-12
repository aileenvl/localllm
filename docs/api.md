# HTTP API

The server exposes three endpoints. Everything except `/health` is
gated by the optional API key configured in **Settings → API key**.
When the key is empty (the default), all endpoints are open.

| Path | Auth | Notes |
|---|---|---|
| [`GET /health`](#health) | always open | Liveness + which backend each cached engine ended up on. |
| [`GET /v1/models`](#models) | optional bearer | Lists `.litertlm` files on disk. |
| [`POST /v1/chat/completions`](#chat-completions) | optional bearer | OpenAI-style. Streaming or blocking. |

## `GET /health` { #health }

Never gated. Use this for liveness / readiness probes.

```bash
curl -s http://localhost:8099/health
```

```json
{
  "status": "ok",
  "service": "localllm-android",
  "version": "1.0",
  "queue_depth": 0,
  "engines_loaded": 1,
  "engines": [
    { "key": "gemma-4-e2b_model_AUTO", "backend": "CPU" }
  ]
}
```

- `queue_depth` — requests currently queued behind the inference
  mutex.
- `engines_loaded` — count of engines in the LRU cache.
- `engines[].key` — engine cache key in the shape
  `<model>_<maxTokens|"model">_<backend>`.
- `engines[].backend` — the backend the engine actually initialized
  on, after the AUTO fallback resolved. Either `"CPU"` or `"GPU"`.

## `GET /v1/models` { #models }

Lists `.litertlm` files currently on disk.

```bash
curl -s -H "Authorization: Bearer $LLM_KEY" \
  http://localhost:8099/v1/models
```

```json
{
  "object": "list",
  "data": [
    {
      "id": "gemma-4-e2b",
      "object": "model",
      "created": 1778610084,
      "owned_by": "local"
    }
  ]
}
```

`id` is the filename with `.litertlm` stripped. Use it in the `model`
field of chat-completion requests.

## `POST /v1/chat/completions` { #chat-completions }

OpenAI-compatible chat completion. Streaming and blocking.

### Request

| Field | Type | Default | Notes |
|---|---|---|---|
| `model` | string | required | An `id` from `GET /v1/models`. |
| `messages` | array | required | Each item is `{role, content}` where `role` is `"system"`, `"user"`, or `"assistant"`. The first system message becomes `ConversationConfig.systemInstruction`; remaining messages are fed as conversation history. The last message must be `user`. |
| `stream` | bool | `false` | Server-Sent Events when true. |
| `session_id` | string | `null` | Stable opaque ID for KV-cache reuse across turns. See [multi-turn](#multi-turn-with-session_id). |
| `temperature` | float | server default | Per-request sampler temperature. |
| `top_k` | int | server default | Per-request sampler top-k. |
| `max_tokens` | int | model default | Per-request total-token budget (input + output). Omit to use the model's compiled budget — overriding it down can trigger `DYNAMIC_UPDATE_SLICE` shape mismatches on big-context Gemma 4 weights. |

### Blocking response

```json
{
  "id": "chatcmpl-1",
  "object": "chat.completion",
  "created": 1778611764,
  "model": "gemma-4-e2b",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "Hi! How can I help?" },
      "finish_reason": "stop"
    }
  ]
}
```

### Streaming response

SSE frames are emitted as the model generates tokens. The first frame
contains `delta.role = "assistant"`; subsequent frames carry
`delta.content`; the final frame carries `finish_reason: "stop"`. The
stream terminates with `data: [DONE]`.

```
data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610331,"model":"gemma-4-e2b","choices":[{"delta":{"role":"assistant"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610333,"model":"gemma-4-e2b","choices":[{"delta":{"content":"Hi"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610334,"model":"gemma-4-e2b","choices":[{"delta":{"content":"!"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610335,"model":"gemma-4-e2b","choices":[{"delta":{},"finish_reason":"stop","index":0}]}

data: [DONE]
```

A heartbeat comment (`: ka\n\n`) is emitted every 10 seconds so
intermediaries don't kill long prefill times.

### Error responses

| Code | Shape | Meaning |
|---|---|---|
| `400` | `ErrorResponse` | Malformed JSON body. |
| `401` | `ErrorResponse` | Missing / invalid bearer token. |
| `408` | `ErrorResponse` | Request timeout (configurable in Settings). |
| `413` | `ErrorResponse` | Prompt or body exceeds the configured cap. |
| `429` | `ErrorResponse` + `Retry-After: 5` | Queue full (default 8 in flight). |
| `500` | `ErrorResponse` | Server / engine error. |

`ErrorResponse` schema:

```json
{ "error": { "message": "Inference timeout", "type": "timeout", "code": 408 } }
```

Errors **mid-stream** are delivered as a final SSE chunk + the `[DONE]`
sentinel — never as a silently-closed connection:

```
data: {"error":{"message":"Inference timeout","type":"timeout","code":408}}

data: [DONE]
```

## Multi-turn with `session_id`

Pass a stable `session_id` and the server caches the underlying
`Conversation` object across calls. Follow-up requests only need to
re-feed *new* user turns; assistant turns echoed by the client are
already in the model's KV cache and are skipped.

```jsonc title="Turn 1"
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [{"role": "user", "content": "Hi"}]
}
```

```jsonc title="Turn 2 — same session_id, full history echoed (OpenAI convention)"
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"},
    {"role": "assistant", "content": "Hello! How can I help?"},
    {"role": "user", "content": "Tell me about kernels."}
  ]
}
```

Server-side rules:

1. **Empty / missing `session_id`** → fresh `Conversation` every
   request. Default.
2. **Non-empty `session_id`** → cached `Conversation`. The server
   validates the replayed prefix via a stable hash of all
   `messages[0..seenCount]`. Mismatch → rebuild from scratch.
3. **Sampling parameter change mid-session** (`temperature` /
   `top_k`) → rebuild. Those are conversation-construction
   parameters in LiteRT-LM's `SamplerConfig`.
4. **Cache size** is 4. Oldest evicted. Conversations are also
   evicted together with their parent engine when the engine is
   unloaded.

On a 10-turn conversation, request N pays only the cost of prefilling
turn N's new user message, not the entire history each time.

## What's not implemented (yet)

The OpenAI-compat surface is intentionally partial. Missing pieces,
in roughly the order they'll land:

- `logprobs` / `top_logprobs` — LiteRT-LM exposes them but they're
  not wired through.
- `n` > 1 — single completion per request.
- `stop` sequences — parsed but ignored.
- `tools` / function calling — LiteRT-LM supports it natively
  (`ConversationConfig.tools`), the HTTP layer doesn't.
- Vision / audio `content` blocks — LiteRT-LM `Content.ImageBytes`
  and `Content.AudioBytes` exist but the route only extracts
  `Content.Text`.

If you need any of these, [open an issue](https://github.com/mlnomadpy/localllm/issues)
or jump to [development](development.md).
