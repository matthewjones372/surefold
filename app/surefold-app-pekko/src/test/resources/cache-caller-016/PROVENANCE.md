# Published 0.16.0 Cache caller fixture

Generated on 2026-10-30 using Kotlin K2JVMCompiler 2.4.10 and Temurin JDK 25.0.4.1.
The checked-in source and bytecode use only the published 0.16.0 Cache API.
Fetched artifacts: `io.github.matthewjones372:surefold:0.16.0` and `io.github.matthewjones372:surefold-cache:0.16.0`.
Artifact base: `https://repo.maven.apache.org/maven2/io/github/matthewjones372/{surefold,surefold-cache}/0.16.0/`.
Both JARs match Central's published SHA-1: core `bdc3eaa17318bd8f6c5e10000b8994e2c707636b`, cache `aec04567f2762306c50e47c26f4b8d8e176e6f83`.
Central's SHA-256 sidecars were unavailable; these SHA-256 values were computed after SHA-1 verification:

| File | SHA-256 |
| --- | --- |
| core 0.16.0 JAR | `949b5fc79fd8201a093c79e8cf26778c0c438646a3c615950da18ecaa9ab089d` |
| cache 0.16.0 JAR | `deaf1a5b5a55ac18cc257cdcbf7d5abeccfae4c81edb2a54b6793385157e6266` |
| CacheCaller016.kt | `dbde4ea58d19a10a0e034ee8ddd33160a5585367829c084d452b1193d46be034` |
| bytecode/compatibility/cache016/CacheCaller016.class | `5cdbaddaefa3f1f6c2845b1c40e270a9a5390ae863da0514707cf0b8a46c3c9f` |
| bytecode/META-INF/main.kotlin_module | `06d262cf732152000d8cb1453e8dc1209b37fa4411e5c4d202265ff0d18ec16d` |

Reproduce with K2JVMCompiler's normal compiler runtime classpath and this invocation:
`java -cp "$COMPILER_RUNTIME" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$CORE_016:$CACHE_016:$STDLIB_2410" -d bytecode CacheCaller016.kt`.
The fixture compilation/runtime classpath contained only those two pinned old JARs and Kotlin stdlib 2.4.10; no current Surefold outputs.
Compilation and execution of `CacheCaller016.main` on that pinned old runtime both exited 0.
The tests compile this source against the current classpath and separately execute the preserved bytecode using current library classes.
Only the nested fixture directory enters the child loader; old Surefold JARs are not checked in or loaded by the tests.
Exercises default constructor bridge, finite get/Right, stats/loads before refresh, refresh, Key/All invalidation, Kotlin use and AutoCloseable.close.
No post-refresh timing assertion, reentrant layer, retention extreme, interruption, awaited release or all-API compatibility is claimed.

Retired at 0.18.0 (spec 0277): the package moved from `dev.surefold` to `dev.surefold`, so bytecode
compiled against 0.16.0 cannot link to current classes. The bytecode fixture and the test that ran it were removed;
`CacheCaller016.kt` remains, with its imports moved to `dev.surefold`, and the tests still compile and run it against the
current classes. The SHA-256 values above describe the files as they were generated, before that change.
