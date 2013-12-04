package org.apache.xmysqld;

import org.apache.jute.BinaryOutputArchive;
import org.apache.xmysqld.algorithm.CircleQueue;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.proto.AllocateRequest;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
	 * when propose , leader need increment the autoIncrementId by idSizeOneNo*AllMemberSize
	 */
	private int idSizeOneNo;
	private String path;
	public AutoIncrementIDPool(int idSizeOneNo, String path){
		this.idSizeOneNo=idSizeOneNo;
		this.path=path;
	}

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
			while (circleQueue.remove(element)==null){//the pool is empty, let's move
				try {
					QuorumPeer qp=QuorumPeerMain.quorumPeer;
					try {
						qp.getActiveServer().firstProcessor.processRequest(createBB());
					} catch (RequestProcessor.RequestProcessorException e) {
						e.printStackTrace();
					}
					wait();
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

	private Request createBB() {
		Request si=null;
		RequestHeader requestHeader = new RequestHeader();
		requestHeader.setType(ZooDefs.OpCode.allocate);
		AllocateRequest request = new AllocateRequest();
		request.setPath(path);
		request.setData(null);
		request.setVersion(-1);
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
			boa.writeInt(-1, "len"); // We'll fill this in later
			requestHeader.serialize(boa, "header");
			request.serialize(boa, "request");
			baos.close();
			ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());//pos==0
			bb.putInt(bb.capacity() - 4);
			bb.rewind();
			si = new Request(null, 0l, 0,ZooDefs.OpCode.allocate, bb, null);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return si;
	}
}
