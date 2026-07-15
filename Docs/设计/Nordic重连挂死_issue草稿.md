# Nordic 上游 issue 草稿（待用户过目后由用户提交）

> 目标仓库：<https://github.com/NordicSemiconductor/Kotlin-BLE-Library>
> 版本：2.0.0-beta03 · 根因分析见 [`Nordic重连挂死_根因分析.md`](Nordic重连挂死_根因分析.md)
> **状态：草稿。提交属对外发布动作，须用户确认后由用户自行提交。**
> 正文用英文（上游为国际项目），保持可直接粘贴。

---

**Title:** Service discovery can permanently deadlock all GATT operations process-wide (ignored `discoverServices()` return value + raw `OperationMutex.lock()` without `finally`)

**Version:** 2.0.0-beta03 (`no.nordicsemi.kotlin.ble:client-android`)

## Summary

Two defects in `Peripheral.discoverServices()` combine so that a single failed service discovery permanently wedges **every** GATT operation in the whole process (all peripherals, all `CentralManager` instances). Recovery requires killing the app process; in our field testing the Android stack itself eventually needs a Bluetooth off/on cycle.

## Defect 1 — the `discoverServices()` return value is ignored

`client-core` `Peripheral.kt:201` declares:

```kotlin
suspend fun discoverServices(uuids: List<Uuid>): Boolean
```

`client-android` `NativeExecutor.kt:139-142` implements it as:

```kotlin
override suspend fun discoverServices(uuids: List<Uuid>): Boolean {
    logger?.d(Layer.GATT) { "gatt.discoverServices()" }
    return gatt?.discoverServices() ?: false
}
```

But the only call site, `client-core` `Peripheral.kt:510`, **drops the result**:

```kotlin
_services.update { RemoteServices.Discovering }
impl.discoverServices(uuids)   // <-- Boolean result ignored
```

`BluetoothGatt.discoverServices()` returning `false` is a documented Android outcome ("discovery could not be started"). When that happens, no `onServicesDiscovered` callback ever fires, so `_services` stays in `Discovering` **forever** — no timeout, no error, no `ServiceDiscoveryFailed` event. Callers observing `services()` simply hang.

## Defect 2 — the global `OperationMutex` is then held forever

`discoverServices()` is the **only** place in the library that takes the mutex with a raw `lock()` instead of `withLock { }` (`Peripheral.kt:495-512`):

```kotlin
private fun discoverServices(uuids: List<Uuid>) {
    if (!servicesDiscovered) {
        servicesDiscovered = true
        scope.launch {
            try {
                OperationMutex.lock(ServicesChanged)   // no try/finally around the body
            } catch (e: IllegalStateException) {
                logger?.warn(Layer.GATT, e)            // swallowed -> continues WITHOUT the lock
            }
            logger?.trace(Layer.GATT) { "Discovering services" }
            _services.update { RemoteServices.Discovering }
            impl.discoverServices(uuids)
        }
    }
}
```

There are exactly three unlock paths, and **all three depend on an event arriving**:

- `ServicesDiscovered` -> `unlock` (`Peripheral.kt:411`)
- `ServiceDiscoveryFailed` -> `unlock` (`Peripheral.kt:466`)
- `invalidateServices()` -> `unlock` if `holdsLock` (`Peripheral.kt:354-364`), reached via `handleDisconnection()`, i.e. only when a disconnection event arrives.

Under Defect 1 none of them happens, so the mutex stays locked. Since `OperationMutex` is a process-wide `object` holding a single `Mutex`, and every other GATT operation uses `withLock(owner = null)` (`BaseRemoteCharacteristic.kt:173/223`, `core-android/Peripheral.kt:479/522/599/636/701/743`, ...), **all reads/writes/MTU/PHY/priority requests on all peripherals queue forever**.

There is also a race independent of Defect 1: `servicesDiscovered = true` is set synchronously, but the lock is taken inside `scope.launch`. If a disconnection runs `invalidateServices()` in between, `holdsLock` is still `false` (nothing to unlock), and the launched coroutine then acquires the lock afterwards on a dead GATT — same permanent leak.

## Defect 3 (minor) — the owner token is a shared singleton

The owner is `data object ServicesChanged` (`GattEvent.kt:88`) — one instance shared by every `Peripheral`. Consequences:

- a second peripheral's discovery calls `lock(ServicesChanged)` while the first holds it -> `kotlinx` `Mutex` throws `IllegalStateException` for a re-lock by the same owner -> the library **swallows it** and proceeds to `impl.discoverServices()` **without holding the lock**, defeating the serialization the mutex exists to provide;
- any peripheral's unlock releases any other peripheral's lock, so the owner check provides no protection.

## Reproduction

A pure-JVM test (no device or Bluetooth adapter needed) that pins the mutex behaviours — process-wide scope, leak-on-cancel of the raw `lock()`, and the shared-owner-token consequences:

<!-- 提交时把 app/src/test/java/io/bluetrace/data/android/NordicOperationMutexPinTest.kt 的三个用例
     翻成英文精简版贴在这里(去掉项目专有注释), 或直接附 gist 链接. -->

In the field (Android 13, Xiaomi M2101K9C): app-initiated disconnect followed by an in-process reconnect to the same device. The physical link and MTU negotiation succeed (mtu=247), then service discovery hangs in `Discovering`; every retry hangs; only killing the process recovers, and after repeated occurrences the system stack needs a Bluetooth off/on cycle. Notably the MTU request **succeeds** on the reconnect — it goes through `withLock`, proving the mutex was still free at that point, i.e. the hang creates the ghost lock rather than being caused by it.

## Suggested fix

1. **Honour the return value** — treat `false` as a failure and emit `ServiceDiscoveryFailed` (which also releases the lock and moves `_services` to `Failed`):

   ```kotlin
   if (!impl.discoverServices(uuids)) {
       // emit ServiceDiscoveryFailed(reason = ...) so observers and the mutex both unblock
   }
   ```

2. **Make the lock exception-safe** — use `OperationMutex.withLock(ServicesChanged) { ... }`, or wrap the body in `try/finally`, so cancellation and unexpected paths cannot leak it.

3. **Consider a timeout** on the discovery wait, so a missing callback degrades to a failure rather than a permanent process-wide stall.

4. **Consider a per-peripheral owner token** (e.g. the `Peripheral` instance) rather than the shared `ServicesChanged` object, so re-lock detection and owner checks are meaningful.

Happy to open a PR if useful.
