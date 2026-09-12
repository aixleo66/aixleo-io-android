[简体中文](../cn/GATEWAY.md) | [English](GATEWAY.md)

# Optional knowledge Gateway contract — experimental reference

## Origin and scope of this version

This protocol comes from **Rokid Local Harness Bridge** (`rokid-local-harness-bridge`, or Rokid Harness), originally designed to connect Rokid glasses to local Codex and a knowledge base on a computer. This is why its identifier remains `rokid-harness.v1`. It is one exploratory way for the RayNeo Android client to reach local Codex, not the only approach or a required standard. More flexible integration approaches are still being explored.

**This version does not contain the Rokid Harness project or its server implementation, and does not add or distribute it.** It retains only the experimental Android compatibility client (`KnowledgeClient.java`, `KnowledgeRunState.java`) and related configuration. The computer-side Gateway, Codex invocation adapter, knowledge retrieval service, tunnel launch scripts, and deployment configuration are not included.

This integration has not received sufficient usability or stability validation for this version. Historical development connection/Q&A attempts are not full acceptance of a deployable service. The contract below documents the existing client; it does not promise a delivered or validated Harness server. You can skip this experimental feature and configure a separate model service. Recording and notifications do not require Rokid Harness.

## Existing client protocol

The repository contains the Android client only, with no computer-side Gateway, knowledge base, or Codex runtime. This is the dedicated `rokid-harness.v1` WebSocket protocol, not an arbitrary OpenAI-compatible HTTP API. The implementation in `app/src/KnowledgeClient.java` and `KnowledgeRunState.java` is authoritative.

Configure `wss://your-domain/service-path` and a separate token. The client rejects non-WSS URLs, query strings, userinfo, and fragments. Put the token in the connection's `hello` message, not the URL. If a temporary tunnel changes domain, update the configuration. The server is responsible for authentication and actual access controls.

Minimum successful exchange, with placeholders for credentials and content:

```json
{"type":"hello","token":"YOUR_TOKEN"}
{"type":"sync","protocol":"rokid-harness.v1"}
{"type":"prompt","prompt":"User question","capability":"read"}
{"type":"event","runId":"example-run","seq":1,"event":{"type":"system"}}
{"type":"event","runId":"example-run","seq":2,"event":{"type":"result","displayAnswer":"Full answer","spokenAnswer":"Short answer","sources":[{"path":"notes/example.md","title":"Source title"}]}}
{"type":"runEnd","runId":"example-run","status":"done"}
```

The client sends the first and third messages; the server sends the others. The first event must be `system` with `seq=1`. Other events may follow, using the same `runId` and consecutive sequence numbers. Duplicate events are ignored; missing sequence numbers fail the request. Exactly one structured `result`, followed by successful `runEnd`, is required. Logs and narrative events are not treated as answers.

Questions must contain 1–2000 characters. `displayAnswer` and `spokenAnswer` must be nonempty and are limited to 16000 and 2000 Unicode code points respectively. `sources` must be an array; it may be empty and may contain at most 50 entries. Each entry requires `path` and `title`, limited to 1024 and 300 characters. Use relative paths, not computer drive letters or absolute paths. The client does not prove that a source was retrieved; the server is responsible for provenance accuracy.

After learning the `runId`, the client reconnects at most once on transport loss, adding `lastRunId` and `lastSeq` to `hello`. The server must support replay/resumption. The client does not automatically resubmit the prompt. Authentication errors, protocol errors, rejection, or an execution `error` fail the request. The overall deadline is 150 seconds. The client keeps no knowledge-service connection open while idle.

Local cancellation closes the connection and stops waiting, but the client currently sends no task-cancellation command to the server. It cannot guarantee that the computer stops execution or charging immediately. The server must manage disconnections and task lifetime. `capability: read` is a request convention; the server must enforce permissions. The phone does not present or approve privileged computer-side actions such as writes or command execution on the user's behalf.
