package org.apache.xmysqld;

import org.apache.xmysqld.algorithm.CircleQueue;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * Created with IntelliJ IDEA.
 * User: ye
 * Date: 13-11-27
 * Time: 上午9:36
 * To change this template use File | Settings | File Templates.
 */
public class AutoIncrementIDPool {
	/**
	 * when propose , leader need increment the value by idSizeOneNo*AllMemberSize
	 */
	private static int idSizeOneNo;
	private static CircleQueue circleQueue;
	static {//just for demo,need load from config when start
		idSizeOneNo=20;
	  	circleQueue=new CircleQueue(100,10);
	}

	/**
	 *
	 * @param finalNo
	 * @param maxId
	 */
	public static boolean add(int finalNo, long maxId){
		byte[] finalNoByte = BigInteger.valueOf(finalNo).toByteArray();
		ByteBuffer byteBuffer=ByteBuffer.allocate(10).put(finalNoByte,finalNoByte.length-3,2).putLong(maxId);
		byteBuffer.flip();
		byte[] element= byteBuffer.array();
		return circleQueue.add(element);
	}
}
