# kernel/ — vendored billion-context kernel

This directory contains an **unmodified** copy of the upstream
[acp-kernel](https://github.com/ranxianglei/acp-kernel) bundle (the compression core
of [billion-context](https://github.com/ranxianglei/billion-context)) plus one small
host seam written for this project.

| File | Origin | Notes |
| --- | --- | --- |
| `index.js` (321 KB) | upstream `acp-kernel@0.0.101` | entry bundle, not modified |
| `chunk-Q3P5Z2PV.js` (27 KB) | upstream | code-split chunk, not modified |
| `chunk-MXL3G3BN.js` (2 KB) | upstream | code-split chunk, not modified |
| `shim-node.js` (1.2 KB) | this project | browser-safe stand-in for the four Node builtins the kernel imports (`fs`, `path`, `crypto`, `os`-like helpers) |
| `host.html` | this project | invisible WebView host: dynamically imports `vc-memory.js` and exposes it to Kotlin via `addJavascriptInterface` |
| `vc-memory.js` | this project | the **only** seam: 9 methods the Kotlin side calls (append / query / compact / stats …). Contains the mobile parameter overrides (working set 6K tokens, `growthFloor=300`, `compress.minCompressRange=250`, `tier2Trigger=4`, `tier3Trigger=8`, `preserveRecentMessages=4`) |

## Licensing

- Upstream `acp-kernel` / `billion-context`: **MIT + an additional attribution
  clause** — any end-user-visible product must credit billion-context (and
  acp-kernel) in its home page, docs, or acknowledgements, with links to the
  upstream repositories. Full text as received: [`../LICENSES/billion-context-LICENSE.txt`](../LICENSES/billion-context-LICENSE.txt)
  (also [`../LICENSES/MIT.txt`](../LICENSES/MIT.txt)).
- This project already satisfies the clause in
  [`../README.md`](../README.md#致谢与署名) and
  [`../README.en.md`](../README.en.md#acknowledgements). **If you fork and rebrand,
  keep that attribution.**

## How to upgrade the kernel

1. `npm pack acp-kernel` (or copy from `node_modules/acp-kernel/dist/`) at the
   desired version.
2. Replace `index.js` and every `chunk-*.js`; drop chunks that disappeared.
3. Re-apply the browser shim import rewrite in the entry bundle: the upstream
   bundle imports four Node builtins, and `shim-node.js` must be wired in their
   place (see the import block at the top of `index.js`).
4. Run `bash tools/fetch_models.sh` if needed, then build and exercise the memory
   path (Settings → memory / a 10+ turn conversation).

Kernel parameters tuned for a 1M-token desktop context are **not** suitable for
companion chat; the overrides live in `vc-memory.js`, not in the vendored files, so
replacing the bundle keeps them intact.
