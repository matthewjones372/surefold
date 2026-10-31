# 0269 - Recover an Either without losing unmatched failures

Owner approved 31 October 2026: total/selective recover with a Fail context, tests, committed spec and
implementation, a local snapshot and appropriate consumer adoption; unexpected captured failures stay values.

## Problem

An Either caller supplying a fallback repeats fold/when and explicit failure forwarding. Selective recovery
must leave unmatched Left values intact, rather than rethrowing their payloads or treating them as success.

## Not doing

No new capture/catchSome/catchAll names, recoverWith, compiler plugin, dependency, automatic retry,
exception inference, public error union or remote publication. Existing catch, attempt and block recover stay.
Recovery does not capture arbitrary handler exceptions: external calls use Either.catch and bind explicitly.
Cancellation, interruption, fatal errors and enclosing Fail unwinding retain existing control-flow semantics.

## Shape

A total extension `Either<E, A>.recover(handler: Fail<F>.(E) -> A): Either<F, A>` runs only for Left;
Right is passed through unchanged. The handler can return A, bind another Either<F, A>, or fail with F.
Without a replacement failure the caller may declare F = Nothing. Nullable results/errors remain valid.

A selective member `recover(type: KClass<T>, handler: Fail<E>.(T) -> A): Either<E, A>` keeps both
existing type arguments fixed. `T : Any`; match using JVM boxed-class assignability, including subtypes.
A null Left never matches a class token. A matching Left runs one handler inside its own attempt.
Unmatched Left and every Right return the original object without invoking the handler. No predicate/list
selector initially; ordinary Kotlin in a selected handler can bind/fail with the unchanged error as needed.
The fixed E prevents inference from admitting an unrelated replacement error; use the total overload when
changing error types. For null recovery, declare the input result's A nullable at its capture boundary.

```kotlin
val response: Either<Throwable, Response?> = Either.catch { external.readResponse() }
val recovered = response.recover(IOException::class) { null }
val backup = response.recover(IOException::class) { readBackup().bind() }
```

These sketches use application-owned Response/readBackup placeholders; compiled guide tests use real types.
Handlers are lazy, run once, and complete before the operator returns. Exceptions thrown by a handler are
not implicitly retried or converted into E/F; typed-failure unwinding belongs only to its owning attempt.
The supported UnsafeVariance annotation may express callback inputs on covariant Either; no internal compiler
annotations or unchecked value casts. Class.cast proves a selected value's type.

## Why this shape

One operator composes ordinary values and fallible alternatives through existing Fail/bind. A member keeps
selective inference honest; a top-level extension naturally permits a new total error type. Avoid a second
branching DSL, partial functions, repeated else/fail forwarding and separate value/Either recovery operators.

## Stack

- [ ] **`codex/0269-either-recover`** - both operators, runtime/compiler tests and core-guide example.
      Done when the tests below and the full build pass. One review slice under 200 changed main/doc lines.

Build from declared snapshot source 8891c816, retaining cond and existing KMS capabilities. Publish a new
traceable 23-module local snapshot only after verification; the consumer has its own committed adoption amendment.

## Acceptance

Test first: absent API, then success/unmatched identity and laziness, selected subtype/boxed/null handling,
exactly-once handlers, bind/fail alternatives, nullable payloads, Nothing/changed total types, handler throws,
enclosing Fail/interruption/cancellation/fatal propagation and positive/negative compiler-inference cases.
Show a deliberately incorrect always-pass-through implementation fails meaningful runtime assertions.
Compile the guide example; no new dependency or weakened coverage/lint gate.

```bash
./gradlew :surefold:test --tests '*EitherRecoverTest*'
./gradlew spotlessApply
./gradlew build --continue --no-parallel --max-workers=2
```

## Open questions

Resolved by the owner's approved design: class-token selective recovery, total recovery, Fail-context handlers,
unmatched failures as values, explicit foreign-call capture, local snapshot and bounded consumer adoption.
No unresolved behavior choice; value/error widening for selective recovery is deliberately outside this slice.
