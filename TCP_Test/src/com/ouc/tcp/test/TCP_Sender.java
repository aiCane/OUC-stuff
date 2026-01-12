/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.*;
import com.ouc.tcp.message.*;

import java.util.Vector;

public class TCP_Sender extends TCP_Sender_ADT {

	/* [RDT 3.0] */
	private UDT_Timer timer;
	private UDT_RetransTask retransTask;

	/* [GBN] */
	private int windowSize = 8; // N
	private int base = 1;
	private int nextSeqNum = 1;
	private Vector<TCP_PACKET> windowPackets = new Vector<>();

	private final int APP_DATA_LENGTH = 100;

	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}

	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；需要修改
	public void rdt_send(int dataIndex, int[] appData) {
		// if (nextSeqNum >= base + windowSize * appData.length) { /* [GBN] wrong */
		if (nextSeqNum > (base + windowSize - 1)) { // [GBN] [base, base + windowSize - 1]
			System.out.print(".");
			return;
		}

		// 生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序 appData.length == 100
		tcpH.setTh_seq(nextSeqNum); // [GBN] 包序号设置为字节流号：
		tcpS.setData(appData);
		TCP_PACKET packet = new TCP_PACKET(tcpH, tcpS, destinAddr);
		tcpH.setTh_sum(CheckSum.computeChkSum(packet));
		packet.setTcpH(tcpH);

		//发送TCP数据报
		windowPackets.add(packet);
		udt_send(packet);

		if (base == nextSeqNum) resetTimer(); // [RDT 3.0]

		nextSeqNum++;
	}

	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)4);
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
		client.send(stcpPack);
	}

	@Override
	//需要修改
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if (ackQueue.isEmpty()) return;

		int currentAck = ackQueue.poll(); // may be broken

		/* [GBN] 累计确认是 if (currentAck >= base) 执行逻辑 */
		if (currentAck < base) return; // 这里条件取反可减少缩进

		int curAckedNum = 0;
		while (
			!windowPackets.isEmpty() &&
			windowPackets.get(0).getTcpH().getTh_seq() <= currentAck
		) {
			windowPackets.remove(0);
			curAckedNum++;
		}

		base = currentAck + 1;

		if (windowPackets.isEmpty()) { freeTimer(); } // [RDT 3.0]
		else { resetTimer(); }
	}

	/* [RDT 3.0] */
	private void resetTimer() {
		freeTimer(); // close before it even opened
		timer = new UDT_Timer();
		retransTask = new UDT_RetransTask(client, null) {
			@Override
			public void run() {
				handleTimeout();
			}
		};
		timer.schedule(retransTask, 3000, 3000);
	}

	/* [RDT 3.0] */
	private void freeTimer() {
		if (timer == null) return;
		timer.cancel();
		timer = null;
	}

	private void handleTimeout() {
		resetTimer();
		for (TCP_PACKET p : windowPackets) {
			System.out.println("   Re-transmitting seq: " + p.getTcpH().getTh_seq()); // Debug log
			udt_send(p);
		}
	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改 /* fuck! fuck you!!! */
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
		ackQueue.add(recvPack.getTcpH().getTh_ack());
		System.out.println();
	    //处理ACK报文
	    waitACK();
	}

}
