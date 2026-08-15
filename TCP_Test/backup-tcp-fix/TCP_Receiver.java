/**
 * TCP_Receiver —— 基于真实 TCP 累计确认与流量控制原理的实现。
 *
 *   1. 累计确认：对每个收到的合法报文段回复 ACK，确认号 = RCV.NXT（下一个期望字节）。
 *   2. 乱序缓存：先于期望序号到达的报文段被缓存，待其前面的空缺补齐后按序交付。
 *   3. 流量控制：通过报文段首部的窗口字段 (th_win) 通告接收窗口 = 接收缓冲区 - 已占用字节。
 *   4. 重复段丢弃：序号小于 RCV.NXT 的重复/过期报文段被丢弃（但仍回 ACK 重新确认）。
 *
 * 说明：MSS = 100（一个 appData 数组 = 100 个"字节"）。
 */
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.TCP_PACKET;

public class TCP_Receiver extends TCP_Receiver_ADT {

	/* ==================== 参数 ==================== */
	private static final int MSS = 100;              // 每个报文段承载的字节数
	private static final int RECV_BUFFER = 64 * MSS; // 接收缓冲区总大小(字节)
	private static final int DELIVER_THRESHOLD = 20; // 每积攒 20 组数据交付一次
	private static final byte ERROR_FLAG = 7;        // 错误控制标志

	/* ==================== 接收端状态 ==================== */
	private int rcvNxt = 1;   // RCV.NXT：下一个期望的字节序号（累计确认号）
	private Map<Integer, TCP_PACKET> recvCache = new HashMap<Integer, TCP_PACKET>(); // 乱序缓存

	public TCP_Receiver() {
		super();
		super.initTCP_Receiver(this);
	}

	@Override
	// 接收到数据报：校验后按序交付或缓存，并回复累计确认
	public void rdt_recv(TCP_PACKET recvPack) {
		if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) {
			return;   // 校验和错误，丢弃
		}

		int seq = recvPack.getTcpH().getTh_seq();
		int len = recvPack.getTcpS().getData().length;

		if (seq == rcvNxt) {
			// 按序到达：交付并推进 RCV.NXT
			dataQueue.add(recvPack.getTcpS().getData());
			rcvNxt += len;
			// 交付缓存中已经连续的乱序段
			while (recvCache.containsKey(Integer.valueOf(rcvNxt))) {
				TCP_PACKET p = recvCache.remove(Integer.valueOf(rcvNxt));
				dataQueue.add(p.getTcpS().getData());
				rcvNxt += p.getTcpS().getData().length;
			}
		} else if (seq > rcvNxt) {
			// 乱序到达：缓存，不推进 RCV.NXT
			Integer key = Integer.valueOf(seq);
			if (!recvCache.containsKey(key)) {
				recvCache.put(key, recvPack);
			}
		}
		// seq < rcvNxt：重复/过期报文段，丢弃（但仍会回 ACK 重新确认）

		System.out.println("[RCV] seq=" + seq + " rcvNxt=" + rcvNxt);
		replyAck(recvPack.getSourceAddr());

		if (dataQueue.size() >= DELIVER_THRESHOLD) {
			deliver_data();
		}
	}

	// 回复累计确认：ACK = RCV.NXT，并通告接收窗口
	private void replyAck(InetAddress address) {
		tcpH.setTh_ack(rcvNxt);
		int buffered = (recvCache.size() + dataQueue.size()) * MSS;
		int win = Math.max(RECV_BUFFER - buffered, 0);
		tcpH.setTh_win((short) win);

		TCP_PACKET ackPack = new TCP_PACKET(tcpH, tcpS, address);
		tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
		reply(ackPack);
	}

	@Override
	// 交付数据（将数据写入文件）
	public void deliver_data() {
		File fw = new File("recvData.txt");
		BufferedWriter writer;

		try {
			writer = new BufferedWriter(new FileWriter(fw, true));

			while (!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();

				for (int i = 0; i < data.length; i++) {
					writer.write(String.valueOf(data[i]));
					writer.newLine();
				}

				writer.flush();
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	// 回复 ACK 报文段
	public void reply(TCP_PACKET replyPack) {
		tcpH.setTh_eflag(ERROR_FLAG);
		client.send(replyPack);
	}

}
