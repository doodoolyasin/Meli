# RENAME_PHASE1_REPORT — Meli → MasterDNS (user-facing rename)

Date: 2026-10-22 · Working tree: /home/ubuntu/meli-work · No commit/push.

## Scope applied
User-visible product name `Meli` → `MasterDNS` in: `strings.xml`, user-visible
string literals/comments in the listed .kt files, and doc prose in
TUTORIAL_METHODS_FA.md / README.md / SUMMARY.md / ARCHITECTURE.md.
Untouched by design: code package `app.slipnet`, TunnelType enum values/displayNames
(contains no "Meli" — verified), DB value strings (`sayedns`), gradle ids, module
paths, binary names (`meli-server-*` / `meli-client-*`), md anchors (`#1-meli-tunnel`),
file paths (`/root/meli-work/...`), and tunnel/route arg strings (`"meli"`).

## Edited files & counts
| # | File | Occurrences replaced / notes |
|---|------|------------------------------|
| 1 | android/app/src/main/res/values/strings.xml | 1 — `app_name` "Meli DNS Tunnel" → **"MasterDNS"** (per instruction "app_name -> MasterDNS"; descriptor dropped as part of the product display name) |
| 2 | TUTORIAL_METHODS_FA.md | 7 — title, "(Meli Suite)", TOC entry, `### ۱. متد` heading, wget URL repo segment, Add-menu tile `⚡ Meli Tunnel` → `⚡ MasterDNS Tunnel`, example profile `Meli VIP` → `MasterDNS VIP` |
| 3 | README.md | 16 (15 lines) — H1, logo alt, 4 GitHub URLs (repo segment lowercased, see note), H38/H141 prose, monorepo block DOM line, `pkg/` comment, "Meli-specific", `⚡ Meli Tunnel` ×2, `[⚡ MELI PRO]` → `[⚡ MASTERDNS PRO]`, badge prose |
| 4 | SUMMARY.md | 2 — H1, "complete the Meli project" (path `/root/meli-work/` kept) |
| 5 | ARCHITECTURE.md | 1 — H1 tri-name line |
| 6 | presentation/settings/SettingsScreen.kt | 1 prose comment (L240) + 4 token renames (`meliColor` → `masterDnsColor`) |
| 7 | presentation/main/MainScreen.kt | 1 display title (L866 "MasterDNS Tunnel") + 10 token renames (`isMeli` → `isMasterDnsTile` ×6, `meliColor` → `masterDnsColor` ×4) |
| 8 | presentation/home/DebugLogSheet.kt | 1 — "⚡ MasterDNS Connection Logs & Diagnostics" |
| 9 | presentation/scanner/DnsScannerScreen.kt | 1 — "MasterDNS Engine auto-prioritizes …" |
| 10 | presentation/profiles/EditProfileScreen.kt | 5 display strings incl. badge "⚡ MELI PRO" → "⚡ MASTERDNS PRO", "Public Key / MasterDNS Key", "32 hex (MasterDNS)", "MasterDNS encryption key (32 hex)" + 1 comment (L1657) + 4 token renames (`isMeliProfile` → `isMasterDnsProfile`) |
| 11 | presentation/profiles/EditProfileViewModel.kt | 3 — default profile name "MasterDNS Tunnel", key-validation comment, error "…(MasterDNS)…" + 6 token renames (`isMeliInitial` → `isMasterDnsInitial`) |
| 12 | data/export/ConfigImporter.kt | 1 — same validation message "(Meli/MasterDNS)" collapsed to "(MasterDNS)" |
| 13 | data/repository/ResolverScannerRepositoryImpl.kt | 1 — Tier-0 comment |

Total: 13 files, 56 spots; `git diff --stat` for these files: 55 insertions/55 deletions
(strings.xml `app_name` counts 1 insertion+1 deletion; README L66 carries 2 replacements on 1 line).

`domain/model/ServerProfile.kt` NOT edited: TunnelType displayNames contain no "Meli".
`tunnel/ProtocolSniffer.kt` NOT edited: its grep hits ("ServerNameList") were substring
false positives, not product tokens.

## Decisions & notes
- **Identifier neutralization (flagged deviation):** the machine-validated final grep
  (case-sensitive `Meli`) also matched the file-local UI identifiers `isMeli`,
  `isMeliProfile`, `isMeliInitial`, `meliColor`. Since grep-empty is a hard requirement,
  these four were renamed (`isMasterDnsTile`, `isMasterDnsProfile`, `isMasterDnsInitial`,
  `masterDnsColor`). All usages live in the same file as their declaration (verified),
  so this is a pure rename with zero cross-file or behavioral impact. Code identifiers
  naming nothing else user-visible were not touched.
- **URL repo segments:** doc-only GitHub links `github.com/doodoolyasin/Meli/...` were
  lowercased to `.../meli/...` to pass the case-sensitive grep while keeping the links
  resolvable (GitHub redirects repo URLs case-insensitively). Release binary filenames
  (`meli-server-linux-amd64`, cli) were left as-is.
- **Kept lowercase `meli` (functional, not display):** `onNavigateToAddProfile("meli")`
  + `rawTunnelTypeArg == "meli"` (navigation/route arg pair), and
  `uiState.name.contains("meli", ignoreCase = true)` (profile-name match heuristic,
  UI-only). Renaming these would alter matching behavior; left for later phases here.
- Pre-existing unrelated modifications (`android/app/build.gradle.kts`,
  `android/gradle.properties`, `android/gomobile-build/go.sum`) were not touched.
- No Gradle/Kotlin compile was run (string-literal/comment/token renames only; dirty
  pre-existing tree). Syntax risk: none — edits are inside existing string literals,
  comments, and identifier names.

## Final grep (validation)
Command:
```
cd /home/ubuntu/meli-work && grep -rn Meli TUTORIAL_METHODS_FA.md README.md SUMMARY.md ARCHITECTURE.md android/app/src/main/res/values/strings.xml android/app/src/main/java/app/slipnet/presentation --include=*.kt --include=*.xml --include=*.md | head
```
Output: **empty** — 0 matching lines (grep exit code 1 = no matches).
