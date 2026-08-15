package com.ouc.tcp.test;

import java.util.zip.CRC32;

import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;

public class CheckSum {

	/*
	 * 计算 TCP 报文段校验和。
	 * 覆盖：TCP 首部中的 seq、ack，以及 TCP 数据字段的每一个完整 int。
	 *
	 * 说明：java.util.zip.CRC32.update(int) 只使用参数的低 8 位，因此旧实现
	 * (直接 crc.update(data)) 只校验了每个 int 的最低字节，高位被破坏无法被检测。
	 * 这里把每个 int 拆成 4 个字节逐一加入 CRC，保证任意比特错误都能被发现。
	 */
	public static short computeChkSum(TCP_PACKET tcpPack) {
		CRC32 crc = new CRC32();
		TCP_HEADER header = tcpPack.getTcpH();

		updateInt(crc, header.getTh_seq());
		updateInt(crc, header.getTh_ack());

		for (int data : tcpPack.getTcpS().getData()) {
			updateInt(crc, data);
		}

		return (short) crc.getValue();
	}

	/* 把一个 int 的 4 个字节按大端序依次加入 CRC */
	private static void updateInt(CRC32 crc, int value) {
		crc.update((value >>> 24) & 0xFF);
		crc.update((value >>> 16) & 0xFF);
		crc.update((value >>> 8) & 0xFF);
		crc.update(value & 0xFF);
	}

}
