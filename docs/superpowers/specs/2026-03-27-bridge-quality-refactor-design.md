# Bridge Quality Refactor Design

**Goal:** Split the current bridge code along routing, transport, protocol, and Kakao runtime boundaries while introducing a repository-level quality gate that is practical for this mixed Kotlin, Android, TypeScript, Go, and shell codebase.

**Scope:** This phase preserves the public behavior of the current bridge flows. It adds only low-risk performance improvements that do not intentionally change queue semantics or public contracts.

## Current Problems

1. `H2cDispatcher` mixes route resolution, admission, queueing, HTTP transport, retry classification, and shutdown.
2. `ImageBridgeServer` mixes transport concerns with request handling and processes clients serially.
3. `KakaoImageSender` mixes chat room resolution, reflection lookup, proxy creation, and single/multi-image send paths.
4. Repository quality checks are fragmented across Gradle, Make, npm, and shell tests with no single verification entrypoint.

## Design

### App bridge (`app/.../bridge`)

- Keep `H2cDispatcher` as the orchestration entrypoint.
- Extract delivery transport code into a dedicated HTTP delivery client.
- Extract request construction into a dedicated request factory.
- Keep retry schedule policy isolated and reused as-is.
- Preserve per-route queue semantics in this phase.

### Image bridge (`bridge/.../bridge`)

- Keep `ImageBridgeServer` responsible for socket lifecycle and framing only.
- Introduce a request handler abstraction so transport and Kakao-specific behavior are decoupled.
- Split `KakaoImageSender` into focused collaborators:
  - `ChatRoomResolver`
  - `KakaoSendInvocationFactory`
  - `SingleImageSender`
  - `MultiImageSender`
- Cache reflection-derived classes and methods inside the Kakao runtime layer to reduce repeated lookup cost.
- Process accepted clients off the accept loop so one slow send does not block new connections.

### Low-risk performance work

- Add reflection metadata caching for the LSPosed image bridge path.
- Move per-client handling off the `LocalServerSocket.accept()` loop while keeping the request-per-connection protocol.
- Do not change webhook retry ordering semantics in this phase.

### Quality manager

- Add a repository-level `scripts/verify-all.sh` entrypoint.
- Make Gradle enforce bridge-module lint and ktlint consistently.
- Add lightweight architecture guardrails focused on bridge code size and scope instead of a broad unstable static-analysis dependency.
- Keep existing Go and TypeScript quality tools and compose them into the root verification flow.

## Testing

- Add regression tests around the newly extracted bridge components.
- Add image bridge server tests around request handling boundaries and error responses.
- Add tests for reflection cache behavior where the runtime layer can be exercised without Kakao classes.
- Verify the new root quality gate script in addition to focused unit tests.
