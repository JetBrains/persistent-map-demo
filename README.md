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

## Standalone jar

```
./gradlew fatJar
```

builds a single runnable jar with all dependencies merged in, at
`build/libs/persistent-map-demo-standalone.jar` -- e.g. for uploading to a GitHub release.

Run it with plain `java -jar` (requires JDK 17+):
```
java -jar persistent-map-demo-standalone.jar <entry_count> <path>
```

For larger entry counts, add these flags before `-jar` (the fat jar's manifest can't bake them in
the way `gradlew run` does):
```
java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED \
  -jar persistent-map-demo-standalone.jar 30000000 ./index
```

