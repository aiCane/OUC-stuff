Backup of the pre-correction ("mimic") implementation.

Taken from git commit 2f7a0aa "[TCP Reno] finished" (the current HEAD before the
TCP-principle correction). These are the ORIGINAL files, kept so the old version
is never lost.

Files:
  TCP_Sender.java   - old sender (packet-index seq numbers, fake cwnd/dup-ack)
  TCP_Receiver.java - old receiver (mixed GBN/SR behavior)
  CheckSum.java     - old CRC32 checksum (only covered low 8 bits of each int)
