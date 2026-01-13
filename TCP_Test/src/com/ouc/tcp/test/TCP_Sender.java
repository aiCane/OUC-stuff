/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.*;
import com.ouc.tcp.message.*;

import java.util.TimerTask;
import java.util.Map;
import java.util.HashMap;

public class TCP_Sender extends TCP_Sender_ADT {

	/* [RDT 3.0] */
	private UDT_Timer timer;
	// private UDT_RetransTask retransTask;

	/* [GBN] */
	private final int windowSize = 8; // N
	private int base = 1;
	private int nextSeqNum = 1;
	// private TCP_PACKET[] windowPackets = new TCP_PACKET[windowSize];

	/* [SR] */
	private Map<Integer, TCP_PACKET> sendWindow = new HashMap<>();
	private Map<Integer, Boolean> ackMap = new HashMap<>();

	// private final int APP_DATA_LENGTH = 100;

	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}

	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；需要修改
	public void rdt_send(int dataIndex, int[] appData) {
		if (nextSeqNum > (base + windowSize - 1)) { // [GBN] [base, base + windowSize - 1]
			System.out.print(".");
			return;
		}

		// 生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序 appData.length == 100
		TCP_HEADER localH = new TCP_HEADER();
		localH.setTh_seq(nextSeqNum); // [GBN] 包序号设置为字节流号：
		TCP_SEGMENT localS = new TCP_SEGMENT();
		localS.setData(appData);
		TCP_PACKET localP = new TCP_PACKET(localH, localS, destinAddr);
		localH.setTh_sum(CheckSum.computeChkSum(localP));
		localP.setTcpH(localH);

		//发送TCP数据报
		// windowPackets[nextSeqNum % windowSize] = localP;
		sendWindow.put(nextSeqNum, localP);
		ackMap.put(nextSeqNum, false);
		localH.setTh_eflag((byte)7);
		udt_send(localP);

		if (base == nextSeqNum) resetTimer(); // [RDT 3.0]

		nextSeqNum++;
	}

	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		// 设置错误控制标志
		// tcpH.setTh_eflag((byte)7);
		// System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());
		// 发送数据报
		client.send(stcpPack);
	}

	@Override
	//需要修改
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if (ackQueue.isEmpty()) return;

		int currentAck = ackQueue.poll();

		/* [GBN] 累计确认是 if (currentAck >= base) 执行逻辑 */
		// if (currentAck < base) return; // 这里条件取反可减少缩进
		if (currentAck < nextSeqNum) ackMap.put(currentAck, true);

		while (ackMap.containsKey(base) && ackMap.get(base)) {
			sendWindow.remove(base);
			ackMap.remove(base);
			base++;

			if (base < nextSeqNum) { resetTimer(); }
			else { freeTimer(); }
		}
		// base = currentAck + 1;

		// if (base == nextSeqNum) { freeTimer(); } // [RDT 3.0]
		// else if (base < nextSeqNum) { resetTimer(); }
	}

	/* [RDT 3.0] */
	private void resetTimer() {
		freeTimer(); // close before it even opened
		timer = new UDT_Timer();
		TimerTask timeout = new TimerTask() {
			@Override
			public void run() {
				TCP_PACKET p = sendWindow.get(base);
				if (p == null) return;
				udt_send(p);
			}
		};
		timer.schedule(timeout, 2000, 1000);
	}

	/* [RDT 3.0] */
	private void freeTimer() {
		if (timer == null) return;
		timer.cancel();
		timer = null;
	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改 /* fuck! fuck you!!! */
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receiving...");
		if (
			CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum() // ||
			// recvPack.getTcpH().getTh_ack() < base
		) { return; }
		System.out.println("Received :)");
		ackQueue.add(recvPack.getTcpH().getTh_ack());
		System.out.println();
	    //处理ACK报文
	    waitACK();
	}

}
