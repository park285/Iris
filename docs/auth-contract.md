# Auth Contract

Iris request signing canonicalizes the request as:

```text
METHOD + "\n" + TARGET + "\n" + TIMESTAMP_MS + "\n" + NONCE + "\n" + SHA256(body)
```

## Canonical Target

- `TARGET` is `path` when the request has no query string.
- When query parameters exist, Iris percent-encodes each query key and value first.
- Encoded query pairs are then sorted by encoded key, then encoded value.
- The canonical target is joined as `path?key=value&...`.
- Space is encoded as `%20`, not `+`.
- Reserved characters such as `&`, `=`, `%` are percent-encoded inside key/value components.

## Examples

```text
/config
/rooms/42/stats?limit=5&period=7d
/query?room%20name=%ED%95%9C%EA%B8%80%20%EC%B1%84%ED%8C%85&symbols=a%26b%3Dc%25
```
