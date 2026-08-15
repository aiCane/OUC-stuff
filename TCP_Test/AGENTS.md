# AGENTS.md - TCP_Test Project Guide

## Project Overview
Networking-education TCP simulation (OUC TCP protocol teaching system). A framework JAR provides
the channel / server / message classes; student code in `src/com/ouc/tcp/test/` implements the
TCP sender & receiver. `com.ouc.tcp.app.SystemStart` is the entry point: it starts the server
thread, creates `TCP_Receiver`, then `App_Sender.main` reads `ENCDA.tcp` (100000 base64/DES
lines) and feeds 1000 data groups (100 ints each) into `TCP_Sender.rdt_send` with a 10 ms sleep.

Current state: the student code is a **real TCP Reno implementation** — byte-stream sequence
numbers (MSS=100), cumulative ACK (ACK = next expected byte), sliding window =
min(cwnd, rwnd) with receiver-advertised flow control, slow start / congestion avoidance /
fast retransmit / fast recovery, and a single retransmission timer with RTO exponential backoff.
Older commits implemented RDT 2.x → GBN → SR → Tahoe → Reno; both the old and the corrected code
are preserved (see "Restoring versions" below).

## CRITICAL: Use Java 8, not the system JDK 21
The framework JAR calls the removed `sun.misc.BASE64Decoder`, so the app **crashes on JDK 9+**
(`NoClassDefFoundError`), and javac 21 cannot produce class files Java 8 can load. Use an
Amazon Corretto 8 JDK (installed on this machine — find them with
`/usr/libexec/java_home -V`). Verified path:
`/Users/yilin/Library/Java/JavaVirtualMachines/corretto-1.8.0_482/Contents/Home`

## Build & Run (Java 8)
```bash
J8=/Users/yilin/Library/Java/JavaVirtualMachines/corretto-1.8.0_482/Contents/Home/bin

# build
rm -rf bin/com/ouc/tcp/test/*.class
$J8/javac -cp "lib/TCP_TestSys_Linux.jar" -d bin src/com/ouc/tcp/test/*.java

# run — feed a newline: App_Sender waits for a keypress on stdin
echo | $J8/java -cp "bin:lib/TCP_TestSys_Linux.jar" com.ouc.tcp.app.SystemStart
```
- Run from the project root: `recvData.txt` / `Log.txt` paths are relative to cwd.
- The JVM **never exits on its own** (server/receiver/sender threads block on sockets). Run it in
  the background, inspect, then clean up: `pkill -f com.ouc.tcp.app.SystemStart`.
- Killing the launching shell does NOT kill the orphaned java child, and an orphaned JVM holds
  ports 8008/9001/9002 → the next run dies with `java.net.BindException: Address already in use`.
  Always `pkill -f com.ouc.tcp.app.SystemStart` before re-running.

## Verifying a run (there are no unit tests)
The only oracles are the console log and two files:
- `recvData.txt` — a complete run ends with **exactly 100000 lines** (1000 groups × 100 ints).
  Any other count means data was lost, corrupted, duplicated, or a run is still pending.
- `Log.txt` — server-side stats (header + `TOTAL/SUC_RATIO/NORMAL/WRONG/LOSS/DELAY` per endpoint).
- Console markers printed by the student code: `[SND]`, `[ACK]`, `[RCV]`, `[FAST-RETX]`,
  `[RETX]`, `[TIMEOUT]`.

Happy path (fast, deterministic): set `ERROR_FLAG = 0` in `TCP_Sender` and `TCP_Receiver`,
rebuild, run ~15 s → 100000 lines, and `[ACK]` shows cwnd growing (slow start then congestion
avoidance). Restore `ERROR_FLAG = 7` afterwards.
Error path: `ERROR_FLAG = 7` (~1% of DATA and ACK packets are corrupted / dropped / delayed
20-40 s); run ~35 s → recvData.txt must still be 100000 lines; expect `[FAST-RETX]` /
`[TIMEOUT]` events and WRONG/LOSS/DELAY rows in Log.txt.

## Framework facts (not obvious from the code)
- Channel error model (`Server.listenNewPacket`): with probability 1/100 a packet is altered per
  the header's `th_eflag`: 1=corrupt payload (or ack field), 2=drop, 3=delay 20-40 s; `eflag=7`
  = all error types. Applies to DATA **and** ACK packets.
- Packet classification (`TCP_TOOL.judgePacketType`): ACK flag + non-empty data = DATA packet;
  ACK flag + empty data = pure ACK. The default `new TCP_HEADER()` already sets the ACK flag, so
  no flag juggling is needed for data or ACK segments.
- `TCP_HEADER` fields in use: `th_seq`, `th_ack`, `th_win` (advertised window, short),
  `th_sum` (checksum), `th_eflag`. It also has `th_mss` (default 1024), SACK fields and
  SYN/ACK/FIN flags that the no-handshake sim does not use.
- Inherited ADT state: sender → `client`, `ackQueue`, `sendBuffer`, `tcpH`, `tcpS`,
  `destinAddr`; receiver → `client`, `dataQueue`, `recvBuffer`, `tcpH`, `tcpS`.
- Threading: `rdt_send` runs on the main thread, `recv`/`waitACK` on `ListenACK`,
  `rdt_recv`/`reply` on `ListenPacket`, the retransmission timer on a `Timer` thread.
  Sender state is shared across threads → guard with `synchronized`.
- `App_Sender` **reuses one `appData` array** and overwrites it between calls: any buffering
  must `clone()` it, or delayed sends read overwritten data.
- Sequence numbers are **byte offsets**: each segment carries 100 bytes, so seqs are 1, 101, 201,
  …; cumulative ACK = next expected byte; the final ACK is 100001.

## Implementation knobs
- `TCP_Sender`: `MSS=100`, `INITIAL_CWND=MSS`, `INITIAL_SSTHRESH=16*MSS`,
  `DUP_ACK_THRESHOLD=3`, `RTO_INITIAL=3000ms`, `RTO_MAX=120000ms`, `ERROR_FLAG`; TCP state
  `base`(SND.UNA), `nextSeqNum`(SND.NXT), `cwnd`, `ssthresh`, `rwnd`, `dupAckCount`,
  `inRecovery`/-`recover`; `unacked` map holds sent-but-unacknowledged segments.
- `TCP_Receiver`: `RECV_BUFFER=64*MSS`, `DELIVER_THRESHOLD=20` (flush to file every 20 groups),
  `rcvNxt`(RCV.NXT), `recvCache` for out-of-order segments; advertises `th_win` for flow control.
- `CheckSum.computeChkSum` must return `short`; it covers seq, ack and all 4 bytes of every data
  int (CRC32) so any bit flip is detected. Sender and receiver use the same class, so they agree.

## Restoring versions
Two plain-file snapshots live in the repo root:
- `backup-current-version/` — the OLD (pre-correction "mimic") sources + README.
- `backup-tcp-fix/` — the CURRENT corrected (TCP Reno) sources + README (2026-08-16).

Git notes: the old code is exactly what git HEAD (`2f7a0aa` "[TCP Reno] finished") contains; the
corrected code is currently uncommitted working-tree changes.

Restore the OLD version (revert the fix):
```bash
# simplest — plain copies, no git permission needed:
cp backup-current-version/TCP_Sender.java backup-current-version/TCP_Receiver.java backup-current-version/CheckSum.java src/com/ouc/tcp/test/
# or via git (needs .git write access, i.e. outside a file sandbox):
git restore --source=2f7a0aa --worktree -- src/com/ouc/tcp/test/TCP_Sender.java src/com/ouc/tcp/test/TCP_Receiver.java src/com/ouc/tcp/test/CheckSum.java
# then rebuild with Java 8 (see Build & Run)
```
Restore the CORRECTED version again:
```bash
cp backup-tcp-fix/TCP_Sender.java backup-tcp-fix/TCP_Receiver.java backup-tcp-fix/CheckSum.java src/com/ouc/tcp/test/
# then rebuild with Java 8
```

## Code Style Guidelines
- Package root: `com.ouc.tcp`; student code lives in `com.ouc.tcp.test` and must not modify
  `com.ouc.tcp.client` / `com.ouc.tcp.message` / framework packages.
- **Tabs** for indentation; opening brace on the same line; closing brace on its own line; space
  before `(` in control statements (`if (`, `while (`, `for (`).
- Naming: `PascalCase` classes, `camelCase` methods/variables, `UPPER_SNAKE_CASE` constants;
  keep the framework's legacy underscore names for overrides (`rdt_send`, `udt_send`,
  `waitACK`, `TCP_Sender_ADT`, `TCP_PACKET`).
- Imports: standard library first, then `com.ouc.tcp.*`; prefer explicit imports over wildcards.
- Comments: `//` single-line, `/* ... */` block; mixed Chinese/English is fine; keep algorithm
  comments close to the code. Do not change the signature of overridden methods — the framework
  calls them. Target ≤ 100 chars per line.
- Don't swallow exceptions: at least `e.printStackTrace()`.

## Quick Reference
| Task | Command |
|------|---------|
| Compile (Java 8) | `$J8/javac -cp "lib/TCP_TestSys_Linux.jar" -d bin src/com/ouc/tcp/test/*.java` |
| Run (Java 8) | `echo \| $J8/java -cp "bin:lib/TCP_TestSys_Linux.jar" com.ouc.tcp.app.SystemStart` |
| Clean | `rm -rf bin/*` |
| Stop a lingering sim | `pkill -f com.ouc.tcp.app.SystemStart` |
| Revert to old code | `cp backup-current-version/*.java src/com/ouc/tcp/test/` (+ rebuild) |
| Restore corrected code | `cp backup-tcp-fix/*.java src/com/ouc/tcp/test/` (+ rebuild) |
| Inspect framework API | `javap -classpath lib/TCP_TestSys_Linux.jar <class>` |
| Find JDKs | `/usr/libexec/java_home -V` |

---
*This file is intended for agentic coding assistants working in this repository. Keep it updated
as the project evolves.*
