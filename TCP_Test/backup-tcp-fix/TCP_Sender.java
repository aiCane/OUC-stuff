/**
 * TCP_Sender —— 基于真实 TCP（TCP Reno）可靠数据传输原理的实现。
 *
 * 与旧版（把序号当成"第几个包"、把 cwnd / 重复 ACK 做成摆设）不同，本实现严格遵循
 * TCP 的核心机制：
 *   1. 字节流序号：序号表示字节流中的位置。每发送一个报文段，序号前进一个报文段长度
 *      (MSS)，而不是"每发一个包 +1"。
 *   2. 累计确认 (Cumulative ACK)：接收方回复的 ACK 是"下一个期望字节"的序号。
 *   3. 滑动窗口：发送窗口 = min(cwnd, rwnd)，rwnd 为接收方通告窗口(流量控制)。
 *   4. 拥塞控制：慢启动 / 拥塞避免 / 快速重传 / 快速恢复。
 *   5. 单个重传定时器：跟踪最早未确认报文段；超时按 TCP Tahoe 处理，RTO 指数退避。
 *
 * 简化说明：本仿真没有三次握手(SYN/SYN-ACK)，序号从 1 开始；MSS = 100（一个 appData
 * 数组 = 100 个"字节"，每个 int 视作一个字节）。
 */
package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;
import com.ouc.tcp.message.TCP_SEGMENT;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.TimerTask;

public class TCP_Sender extends TCP_Sender_ADT {

	/* ==================== TCP 参数 ==================== */
	private static final int MSS = 100;                 // 每个报文段承载的字节数
	private static final int INITIAL_CWND = MSS;        // 初始拥塞窗口 = 1 个 MSS
	private static final int INITIAL_SSTHRESH = 16 * MSS; // 慢启动阈值初始值
	private static final int DUP_ACK_THRESHOLD = 3;     // 触发快速重传的重复 ACK 数
	private static final long RTO_INITIAL = 3000L;      // 初始超时时间(ms)
	private static final long RTO_MAX = 120000L;        // 超时时间上限(ms)
	private static final byte ERROR_FLAG = 7;           // 错误控制标志：7 = 模拟全部错误类型

	/* ==================== 发送端状态 ==================== */
	private int nextSeqNum = 1;      // SND.NXT：下一个要发送的字节序号
	private int base = 1;            // SND.UNA：最早未确认的字节序号
	private int cwnd = INITIAL_CWND; // 拥塞窗口(字节)
	private int ssthresh = INITIAL_SSTHRESH; // 慢启动阈值(字节)
	private int rwnd = 65535;        // 接收方通告窗口(字节)，初始视为无穷大
	private int dupAckCount = 0;     // 连续重复 ACK 计数
	private boolean inRecovery = false; // 是否处于快速恢复阶段
	private int recover = 0;         // 进入快速恢复时的 SND.NXT

	/* ==================== 缓存结构 ==================== */
	private Queue<int[]> appBuffer = new LinkedList<int[]>(); // 待发送的应用数据
	private Map<Integer, TCP_PACKET> unacked = new HashMap<Integer, TCP_PACKET>(); // 已发送未确认

	/* ==================== 定时器 ==================== */
	private UDT_Timer timer;
	private long rto = RTO_INITIAL;

	public TCP_Sender() {
		super();
		super.initTCP_Sender(this);
	}

	@Override
	// 应用层调用：接收一个 appData 数组（100 个"字节"），加入发送缓冲并尝试发送
	public void rdt_send(int dataIndex, int[] appData) {
		synchronized (this) {
			// 应用层会复用并覆盖 appData 数组，必须克隆，否则延迟发送时会读到被覆盖的数据
			appBuffer.add(appData.clone());
			trySend();
		}
	}

	@Override
	// 不可靠发送：把报文段交给底层信道（设置错误控制标志后发送）
	public void udt_send(TCP_PACKET stcpPack) {
		stcpPack.getTcpH().setTh_eflag(ERROR_FLAG);
		client.send(stcpPack);
	}

	// 只要窗口还有空间且缓冲不为空，就尽量发送
	private void trySend() {
		int effectiveWindow = Math.min(cwnd, rwnd);   // 发送窗口 = min(拥塞窗口, 通告窗口)
		while (!appBuffer.isEmpty()) {
			int inflight = nextSeqNum - base;         // 已发出但未确认的字节数
			if (inflight + MSS > effectiveWindow) break; // 放不下一整个报文段则停止

			int[] data = appBuffer.poll();
			TCP_PACKET packet = buildPacket(nextSeqNum, data);
			unacked.put(Integer.valueOf(nextSeqNum), packet);
			udt_send(packet);
			System.out.println("[SND] seq=" + nextSeqNum + " len=" + data.length
					+ " cwnd=" + cwnd + " ssthresh=" + ssthresh);

			if (base == nextSeqNum) {   // 这是第一个未确认段 → 启动重传定时器
				startTimer();
			}
			nextSeqNum += data.length;
		}
	}

	// 构造一个 TCP 报文段
	private TCP_PACKET buildPacket(int seq, int[] data) {
		TCP_HEADER header = new TCP_HEADER();
		header.setTh_seq(seq);
		TCP_SEGMENT segment = new TCP_SEGMENT();
		segment.setData(data);
		TCP_PACKET packet = new TCP_PACKET(header, segment, destinAddr);
		header.setTh_sum(CheckSum.computeChkSum(packet));
		packet.setTcpH(header);
		return packet;
	}

	@Override
	// 收到 ACK 报文：校验后放入 ackQueue 并处理
	public void recv(TCP_PACKET recvPack) {
		if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) {
			return;   // 校验和错误，丢弃
		}
		synchronized (this) {
			int ack = recvPack.getTcpH().getTh_ack();
			int win = recvPack.getTcpH().getTh_win();
			rwnd = (win < 0) ? 0 : win;   // 更新接收方通告窗口
			ackQueue.add(Integer.valueOf(ack));
			waitACK();
		}
	}

	@Override
	// 处理 ackQueue 中累积的确认号
	public synchronized void waitACK() {
		while (!ackQueue.isEmpty()) {
			int ack = ackQueue.poll().intValue();
			onAck(ack);
		}
		trySend();
	}

	// 处理一个确认号
	private void onAck(int ackNum) {
		if (ackNum > base) {
			handleNewAck(ackNum);   // 新数据被确认
			dupAckCount = 0;
		} else if (ackNum == base) {
			dupAckCount++;          // 重复 ACK
			handleDuplicateAck();
		}
		// ackNum < base：过期(延迟/乱序)的 ACK，忽略
	}

	// 处理"确认了新数据"的 ACK
	private void handleNewAck(int ackNum) {
		rto = RTO_INITIAL;   // 有进展则复位退避

		if (inRecovery) {
			if (ackNum >= recover) {
				// 快速恢复结束（Reno）：窗口收缩回 ssthresh
				cwnd = ssthresh;
				inRecovery = false;
			} else {
				// 部分确认：前面仍有缺失段，推进 base 后立即重传新的缺失段
				base = ackNum;
				removeAcked(ackNum);
				retransmitBase();
				if (base < nextSeqNum) startTimer(); else stopTimer();
				return;
			}
		} else {
			// 正常拥塞控制：慢启动 / 拥塞避免
			if (cwnd < ssthresh) {
				cwnd += MSS;                // 慢启动：每收到一个 ACK 增加一个 MSS（每 RTT 翻倍）
			} else {
				cwnd += (MSS * MSS) / cwnd; // 拥塞避免：每 RTT 约增加一个 MSS
			}
		}

		base = ackNum;
		removeAcked(ackNum);
		System.out.println("[ACK] ack=" + ackNum + " base=" + base
				+ " cwnd=" + cwnd + " ssthresh=" + ssthresh);

		if (base < nextSeqNum) startTimer(); else stopTimer();
	}

	// 处理重复 ACK
	private void handleDuplicateAck() {
		if (inRecovery) {
			cwnd += MSS;   // 快速恢复阶段：每个重复 ACK 膨胀窗口
		} else if (dupAckCount == DUP_ACK_THRESHOLD) {
			// 快速重传：3 个重复 ACK 判定丢包
			ssthresh = Math.max(cwnd / 2, 2 * MSS);
			retransmitBase();
			cwnd = ssthresh + DUP_ACK_THRESHOLD * MSS;
			recover = nextSeqNum;
			inRecovery = true;
			System.out.println("[FAST-RETX] seq=" + base
					+ " ssthresh=" + ssthresh + " cwnd=" + cwnd);
		}
	}

	// 超时处理（TCP Tahoe）
	private void onTimeout(int seq) {
		if (seq != base) return;   // 过期定时器，忽略
		ssthresh = Math.max(cwnd / 2, 2 * MSS);
		cwnd = MSS;
		dupAckCount = 0;
		inRecovery = false;
		recover = 0;
		retransmitBase();
		rto = Math.min(rto * 2, RTO_MAX);   // RTO 指数退避
		System.out.println("[TIMEOUT] seq=" + base
				+ " ssthresh=" + ssthresh + " cwnd=" + cwnd + " rto=" + rto);
		startTimer();
	}

	// 重传最早的未确认报文段
	private void retransmitBase() {
		TCP_PACKET packet = unacked.get(Integer.valueOf(base));
		if (packet != null) {
			udt_send(packet);
			System.out.println("[RETX] seq=" + base);
		}
	}

	// 移除已确认(seq < ackNum)的报文段
	private void removeAcked(int ackNum) {
		Iterator<Integer> it = unacked.keySet().iterator();
		while (it.hasNext()) {
			int seq = it.next().intValue();
			if (seq < ackNum) it.remove();   // ACK 只按整段推进，seq < ackNum 即整段被确认
		}
	}

	// 启动(重启)重传定时器
	private void startTimer() {
		stopTimer();
		timer = new UDT_Timer();
		final int seq = base;
		final TCP_Sender self = this;
		timer.schedule(new TimerTask() {
			public void run() {
				synchronized (self) {
					onTimeout(seq);
				}
			}
		}, rto);
	}

	// 关闭定时器
	private void stopTimer() {
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}

}
