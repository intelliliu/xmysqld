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
	public AutoIncrementIDPool(int idSizeOneNo){
		this.idSizeOneNo=idSizeOneNo;
	}

	/**
	 * when propose , leader need increment the autoIncrementId by idSizeOneNo*AllMemberSize
	 */
	private  int idSizeOneNo;
	private  CircleQueue circleQueue;
	 {//just for demo,need load from config when start
		idSizeOneNo=20;
	  	circleQueue=new CircleQueue(100,10);
	}

	private  long offset;
	private  Long cursor;

	/**
	 *
	 * @param finalNo
	 * @param maxId
	 */
	public boolean add(int finalNo, long maxId){
		byte[] finalNoByte = BigInteger.valueOf(finalNo).toByteArray();
		ByteBuffer byteBuffer=ByteBuffer.allocate(10).putLong(maxId).put(finalNoByte, finalNoByte.length - 3, 2);
		byteBuffer.flip();
		byte[] element= byteBuffer.array();
		synchronized (circleQueue){
			while (!circleQueue.add(element)){
				try {
					wait();//the pool is full
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			notifyAll();
		}
		return true;
	}

	private long remove(){
		byte[] element=new byte[10];
		synchronized (circleQueue){
			while (circleQueue.remove(element)==null){
				try {

					wait();//the pool is empty
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			notifyAll();
		}
		ByteBuffer byteBuffer=ByteBuffer.allocate(8).put(element,0,8);
		byteBuffer.flip();
		long maxId=byteBuffer.getLong();
		byteBuffer=ByteBuffer.allocate(4).put(element,8,2);
		byteBuffer.flip();
		int number=byteBuffer.getInt();
		return maxId-(number+1)*idSizeOneNo;
	}

	public long getId(){
		synchronized (cursor){
			while (cursor<offset+idSizeOneNo){
				return cursor++;
			}
			offset=remove();
			cursor=offset;
			return cursor++;
		}
	}
}
