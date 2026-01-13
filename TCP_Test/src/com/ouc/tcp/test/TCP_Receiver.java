/***************************2.1: ACK/NACK*****************/
/***** Feng Hong; 2015-12-09******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.net.InetAddress;

import java.util.Map;
import java.util.HashMap;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;
// import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Receiver extends TCP_Receiver_ADT {

	private TCP_PACKET ackPack; // 回复的ACK报文段
	private int expectedSeq = 1; // [RDT 2.2] 用于记录当前待接收的包序号，注意包序号不完全是

	/* [SR] */
	private Map<Integer, TCP_PACKET> recvCache = new HashMap<>();
	private final int windowSize = 8;

	// private final int APP_DATA_LENGTH = 100;

	/*构造函数*/
	public TCP_Receiver() {
		super();	//调用超类构造函数
		super.initTCP_Receiver(this);	//初始化TCP接收端
	}

	@Override
	//接收到数据报：检查校验和，设置回复的ACK报文段
	public void rdt_recv(TCP_PACKET recvPack) {
		if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) return;

		int recvSeq = recvPack.getTcpH().getTh_seq();
		System.out.println("Receving " + recvSeq + ", expected " + expectedSeq);
		if (recvSeq < expectedSeq) {
			System.out.println("Replying an old one");
			replyAck(recvSeq, recvPack.getSourceAddr());
		} else if (recvSeq < expectedSeq + windowSize) {
			if (!recvCache.containsKey(recvSeq)) recvCache.put(recvSeq, recvPack);

			while (recvCache.containsKey(expectedSeq)) {
				TCP_PACKET p = recvCache.get(expectedSeq);
				dataQueue.add(p.getTcpS().getData());
				recvCache.remove(expectedSeq);
				expectedSeq++;
			}

			System.out.println("Replying a new one");
			replyAck(recvSeq, recvPack.getSourceAddr());
		} // else // recvSeq >= expectedSeq + windowSize

		//交付数据（每20组数据交付一次）
		if (dataQueue.size() >= 20) deliver_data();
	}

	private void replyAck(int ackNum, InetAddress address) {
		// 生成ACK报文段（设置确认号）
		tcpH.setTh_ack(ackNum);
		ackPack = new TCP_PACKET(tcpH, tcpS, address);
		tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
		//回复ACK报文段
		reply(ackPack);
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;

		try {
			writer = new BufferedWriter(new FileWriter(fw, true));

			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();

				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}

				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);	// eFlag=0，信道无错误
		//发送数据报
		client.send(replyPack);
	}

}
