# Custom Glints

NBT-driven per-item animated enchantment glints. Source lives on a branch per game version.

| MC | Loader | Branch | Maven (`mcmodsrepo`) | Changelog |
|---|---|---|---|---|
| 1.20.1 | Forge 47.x | [`1.20.1`](https://github.com/TunaMods/custom_glint/tree/1.20.1) | `.../custom_glint/1.20.1/mcmodsrepo` | [changelog-1.20.1.txt](changelog-1.20.1.txt) |
| 1.21.1 | NeoForge 21.x | [`1.21.1`](https://github.com/TunaMods/custom_glint/tree/1.21.1) | `.../custom_glint/1.21.1/mcmodsrepo` | [changelog-1.21.1.txt](changelog-1.21.1.txt) |

Maven base: `https://raw.githubusercontent.com/TunaMods/custom_glint/<branch>/mcmodsrepo`

## Artifacts

- `custom-glint-api-<ver>.jar` (`customglint_api`) — rendering pipeline + Java API. Bundle this via jarJar.
- `custom_glint-<ver>.jar` (`customglint` + `customglint_api`) — full standalone, api nested in `META-INF/jarjar/`.

## Bundling (jarJar)

Forge (1.20.1):

```gradle
repositories { maven { url = "https://raw.githubusercontent.com/TunaMods/custom_glint/1.20.1/mcmodsrepo" } }
dependencies {
    compileOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.5.0")
    runtimeOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.5.0")
    jarJar(group: 'net.tunamods.customglint', name: 'custom-glint-api', version: '[1.5.0,2.0)')
}
```

NeoForge (1.21.1):

```gradle
repositories { maven { url = "https://raw.githubusercontent.com/TunaMods/custom_glint/1.21.1/mcmodsrepo" } }
dependencies {
    jarJar(implementation("net.tunamods.customglint:custom-glint-api")) {
        version {
            strictly "[1.5.0,2.0)"
            prefer "1.5.0"
        }
    }
}
```

Pin embedders to the latest version. Per-mod compat mixins (Ice and Fire, Epic Knights, Sophisticated Backpacks, ElytraSlot, FPM) ship only in the full jar, not the api.

Full dev docs (API surface, render hooks, auto-apply registries) are in each branch's `README.txt`.

MIT, attribution required.
