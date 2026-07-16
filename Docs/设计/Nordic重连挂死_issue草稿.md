# Nordic 上游 issue 底稿（v2 · 已提交 [#337](https://github.com/nordicsemi/Kotlin-BLE-Library/issues/337)，本文留档）

> 目标仓库：<https://github.com/NordicSemiconductor/Kotlin-BLE-Library>
> 版本：2.0.0-beta03（commit `78843bf`，即官方 tag）
> 根因分析：[`Nordic重连挂死_根因分析.md`](Nordic重连挂死_根因分析.md)；真机证据：[`../真机证据/nordic25_20260716/`](../真机证据/nordic25_20260716/)
>
> **状态：已提交 —— [NordicSemiconductor/Kotlin-BLE-Library#337](https://github.com/nordicsemi/Kotlin-BLE-Library/issues/337)（2026-07-16，经用户逐段过目后授权，以用户 GitHub 身份提交）。本文自此为底稿留档。**
> **订正评论已发**（2026-07-16，用户放行后以其身份发布）：[issuecomment-4991693289](https://github.com/nordicsemi/Kotlin-BLE-Library/issues/337#issuecomment-4991693289)——A/B 表自写侧口径 8 请求实为 4 连接实例 4/4，Nordic 7/3 不变。
> v2 相对 v1 的升级：v1 只有源码推理；v2 有 **只观测 fork 的三层日志** + **同机同栈的 A/B 对照**（自写 GATT 客户端 8 次 0 挂死 vs 本库 7 次 3 挂死）+ dumpsys 系统层佐证。这是维护者能直接采信的材料。
>
> 正文用英文（上游为国际项目），保持可直接粘贴。

---

**Title:** `discoverServices()` result is dropped and the global `OperationMutex` is taken without `finally` — one failed discovery permanently deadlocks all GATT operations process-wide

**Version:** 2.0.0-beta03 (`no.nordicsemi.kotlin.ble:client-android`), commit `78843bf`

**Device/OS:** Xiaomi M2101K9C / Android 13. Peripherals: 3 different BLE watches (SKG VitaPilot / SKG WATCH S7).

## Summary

Two defects in `Peripheral.discoverServices()` combine so that a single failed service discovery permanently wedges **every** GATT operation in the process (all peripherals, all `CentralManager` instances). Recovery requires killing the app process.

We instrumented an **observation-only fork** of tag `2.0.0-beta03` (logging only, no behaviour change) and ran an A/B against a hand-written `BluetoothGatt` client on the same phone, same peripherals, same Bluetooth stack (no adapter restart between runs). Numbers below.

## Defect 1 — the `discoverServices()` return value is dropped

`client-core` `Peripheral.kt:201` declares:

```kotlin
suspend fun discoverServices(uuids: List<Uuid>): Boolean
```

`client-android` `NativeExecutor.kt:139-142` implements it:

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

`BluetoothGatt.discoverServices()` returning `false` is a documented Android outcome. When it happens, no `onServicesDiscovered` callback ever fires, so `_services` stays `Discovering` **forever** — no timeout, no error, no `ServiceDiscoveryFailed`.

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

Exactly three unlock paths exist, and **all three depend on an event arriving**: `ServicesDiscovered` (`:411`), `ServiceDiscoveryFailed` (`:466`), and `invalidateServices()` (`:354-364`, reached only via `handleDisconnection()`, i.e. only when a disconnection event arrives).

Under Defect 1 none of them happens, so the mutex stays locked. `OperationMutex` is a process-wide `object` holding a single `Mutex`, and every other GATT operation uses `withLock(owner = null)` (`BaseRemoteCharacteristic.kt:173/223`, `core-android/Peripheral.kt:479/522/599/636/701/743`, …) — so **all reads/writes/MTU/PHY/priority requests on all peripherals queue forever**.

**Notably `Peripheral.disconnect()` also takes that mutex first** (`Peripheral.kt:1502`) and its body runs in `withContext(NonCancellable)`. So once the ghost lock exists, the app cannot even disconnect: an outer `withTimeoutOrNull(3s)` around `peripheral.disconnect()` cannot cut it — it only signals cancellation, and the call returns only once the lock is released. We measured this directly (see Evidence, sample 1).

### A race independent of Defect 1

`servicesDiscovered = true` is set synchronously, but the lock is taken inside `scope.launch`. If a disconnection runs `invalidateServices()` in between, `holdsLock` is still `false` (nothing to unlock), and the launched coroutine then acquires the lock afterwards on a dead GATT — same permanent leak. Measured: `disconnect enter` at T, `DISC_REQUEST started=true` at T+2ms, `disconnect stuck` at T+4s.

## Defect 3 (minor) — the owner token is a shared singleton

The owner is `data object ServicesChanged` (`GattEvent.kt:88`) — one instance shared by every `Peripheral`. So a second peripheral's discovery calls `lock(ServicesChanged)` while the first holds it → `kotlinx` `Mutex` throws `IllegalStateException` for a re-lock by the same owner → the library **swallows it** and proceeds to `impl.discoverServices()` **without holding the lock**, defeating the serialization the mutex exists to provide. Also, any peripheral's unlock releases any other peripheral's lock.

## Evidence

### A/B, same phone / same peripherals / same adapter session

"hold" = connect and wait 12 s **without cancelling** (so a missing callback cannot be blamed on teardown):

| Client | discovery requests | no `onServicesDiscovered` |
| --- | --- | --- |
| hand-written `BluetoothGatt` client | 8 | **0** |
| this library (2.0.0-beta03) | 7 | **3 (43 %)** |

The library run had exactly one `connect cancelled`, and it happened **after** all three hangs.

### Three-layer instrumentation (observation-only fork of `78843bf`)

Log points added (logging only; `tryEmit` is still called exactly once, its result stored then logged):

1. `NativeExecutor.discoverServices()` → `started` (the dropped return value)
2. `NativeGattCallback.onServicesDiscovered()` → `status`, `services.size`, `_events.subscriptionCount`, `tryEmit` result
3. `Peripheral.handle(ServicesDiscovered)` → consumption

Findings across ~30 attempts:

- `started=false`: **0** — the framework never refused the request.
- `started=true` with no native `onServicesDiscovered`: **confirmed**, and only with this library.
- `subscriptionCount=0` or `tryEmit=false`: **0** — `DISC_CALLBACK` and `DISC_CONSUMED` counts are always equal, so the `MutableSharedFlow(replay=0)` event pipe is **not** losing events.

So the loss is between `gatt.discoverServices()` returning `true` and the native callback.

### Correlation with the MTU exchange

In our runs the discovery callback was lost **only** when the MTU exchange completed while a discovery was in flight:

| timeline | outcome |
| --- | --- |
| `DISC_REQUEST` → `DISC_CALLBACK` (562 / 624 / 13 ms) → MTU converged | OK (3/3) |
| `DISC_REQUEST` → MTU converged (48–50 ms later) → no callback | hang (3/3) |

This matches the library's own comment right above the `OperationMutex.lock` in `discoverServices` (`Peripheral.kt:500-503`):

> "On older Android versions each Bluetooth operation needs to await its callback before another one can be triggered. Otherwise, **some callbacks aren't called at all**. I.e. discovering services while also requesting HIGH connection priority makes only one of them to complete."

Our app calls `peripheral.services()` **before** `centralManager.connect()` (the documented way to have services discovered on reconnect for existing observers), which makes the library auto-start discovery on `Connected`; concurrently the app calls `peripheral.requestHighestValueLength()`. The hand-written client instead issues `discoverServices()` **only from `onMtuChanged`**, i.e. strictly after the MTU exchange finishes — and never hangs.

We have **not** proven the two native operations actually overlap (both go through `OperationMutex`, and our "MTU converged" marker is an app-side observation, not `onMtuChanged`). We report the correlation, not a mechanism.

## Suggested fix

1. **Honour the return value** — treat `false` as a failure and emit `ServiceDiscoveryFailed`, which also releases the lock and moves `_services` to `Failed`:

   ```kotlin
   if (!impl.discoverServices(uuids)) {
       // emit ServiceDiscoveryFailed(reason = ...) so observers and the mutex both unblock
   }
   ```

2. **Make the lock exception-safe** — use `OperationMutex.withLock(ServicesChanged) { ... }`, or wrap the body in `try/finally`, so cancellation and unexpected paths cannot leak it.

3. **Add a timeout** on the discovery wait, so a missing callback degrades to a failure rather than a permanent process-wide stall.

4. **Consider a per-peripheral owner token** (e.g. the `Peripheral` instance) instead of the shared `ServicesChanged` object, so re-lock detection and owner checks are meaningful.

5. **Consider serializing the MTU exchange and service discovery** on connect (or documenting that `services()` pre-registration must not be combined with an MTU request), given the library's own warning quoted above.

Happy to open a PR, and to share the observation-only patch and raw logs.

---

## 提交前要做的事（给我们自己看，不进 issue 正文）

- [ ] 把三层日志的最小片段贴进 Evidence（或附 gist）：`Docs/真机证据/nordic25_20260716/hold_nordic.log` 与 `ab_selfwritten.log`
- [ ] 观测补丁本身可作为 gist/PR 附上：`E:\_nordic_fork` 的 3 个文件 diff（相对官方 tag，仅日志）
- [ ] 如维护者要 HCI snoop，再补（本轮未做）
