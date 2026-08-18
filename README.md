# persistent-map-demo

A standalone demo of IntelliJ Platform's `com.intellij.util.io.PersistentMapImpl`, pulled in as a
plain Maven dependency (`com.jetbrains.intellij.platform:util`).
It mimics the real `com.intellij.psi.impl.cache.impl.id.IdIndex`: keys are word-hash-derived
`IdIndexEntry`-like values, and each entry's value is a postings list (`ValueContainerImpl`)
mapping an occurrence bitmask to a handful of fake file IDs -- the same on-disk shape a real
IntelliJ Platform index uses, just with synthetic data instead of a real project.

## Run

```
./gradlew run --args="<entry_count> <path>"
```

The following parameters will create an index of ~800MB under "index" subdirectory:
```
./gradlew run --args="30000000 ./index"
```

