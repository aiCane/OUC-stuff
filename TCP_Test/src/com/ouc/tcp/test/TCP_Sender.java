/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.*;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

import java.util.concurrent.ConcurrentHashMap;

public class TCP_Sender extends TCP_Sender_ADT {

	// private TCP_PACKET tcpPack;
	// private volatile int flag = 0;

	/* [Pipeline]: window */
	private int windowSize = 8;
	private int base = 0;
	private int nextSeqNum = 0; // [RDT 2.2] [Pipeline]

	/* [Pipeline]: sending */
	private ConcurrentHashMap<Integer, TCP_PACKET> windowPackates = new ConcurrentHashMap<>();

	/* [Pipeline]: timer */
	private UDT_Timer timer; // [RDT 3.0]
	private UDT_RetransTask retransTask; // [RDT 3.0]
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；需要修改
	public void rdt_send(int dataIndex, int[] appData) {
		/* [Pipeline] */
		while (nextSeqNum >= base + windowSize) {
			try { Thread.sleep(5); } catch (Exception e) {}
		}
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		// tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：
		tcpH.setTh_seq(nextSeqNum); // [RDT 2.2] [Pipeline]
		tcpS.setData(appData);
		TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr); // [Pipeline]: 待发送的TCP数据报
				
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		//发送TCP数据报
		windowPackates.put(nextSeqNum, tcpPack);
		udt_send(tcpPack);

		/* [RDT 3.0] [Pipeline] */
		if (base == nextSeqNum) resetTimer(tcpPack, 3000);

		// flag = 0;

		//等待ACK报文
		//waitACK();
		// while (flag==0);

		// nextSeq = 1 - nextSeq; // [RDT 2.2]
		nextSeqNum++; // [Pipeline]
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);
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
//			/* [Pipeline]: currently set to 3.0 (timeout) */
//			// System.out.println("Retransmit: "+tcpPack.getTcpH().getTh_seq());
//			if (timer != null) timer.cancel(); // [RDT 3.0]
//			flag = 0;
//
//			udt_send(tcpPack);
//
//			/* [RDT 3.0] */
//			timer = new UDT_Timer();
//			retransTask = new UDT_RetransTask(client, tcpPack);
//			timer.schedule(retransTask, 3000, 3000);

		int currentAck = ackQueue.poll();
		System.out.println("CurrentAck: " + currentAck); // [RDT 2.2]
		if (windowPackates.containsKey(currentAck)) {
			// System.out.println("Clear: "+tcpPack.getTcpH().getTh_seq());

			/* [Pipeline] */
			windowPackates.remove(currentAck);

			while (!windowPackates.containsKey(base) && base < nextSeqNum) base++;

			freeTimer();

			if (windowPackates.containsKey(base) && base < nextSeqNum)
				resetTimer(windowPackates.get(base), 3000);
		}
	}

	/* [Pipeline] [RDT 3.0] */
	private void resetTimer(TCP_PACKET packet, long time) {
		timer = new UDT_Timer();
		retransTask = new UDT_RetransTask(client, packet);
		timer.schedule(retransTask, time, time);
	}

	/* [Pipeline] [RDT 3.0] */
	private void freeTimer() {
		if (timer == null) return;
		timer.cancel();
		timer = null;
	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
		ackQueue.add(recvPack.getTcpH().getTh_ack());
	    System.out.println();	
	   
	    //处理ACK报文
	    waitACK();
	   
	}
	
}
