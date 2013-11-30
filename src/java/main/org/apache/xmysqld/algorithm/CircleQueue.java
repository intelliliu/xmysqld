package org.apache.xmysqld.algorithm;

/**
 * Created with IntelliJ IDEA.
 * User: ye
 * Date: 13-11-26
 * Time: 下午11:03
 * To change this template use File | Settings | File Templates.
 */
public class CircleQueue {
	private int queueLength;
	private int elementLength;
	private byte[] queue;
	private int tail,head;

	/**
	 *
	 * @param queueLength
	 * @param elementLength length of each element which describe one finalNo
	 */
	public CircleQueue(int queueLength, int elementLength){
		this.queueLength=queueLength;
		this.elementLength=elementLength;
		queue=new byte[queueLength*elementLength];
		tail=1;
		head=0;
	}

	public boolean add(byte[] element){
		synchronized (queue){
			if (element.length!=elementLength){
				return false;
			}
			if(tail==head){
				return false;
			} else {
				int offset=(tail-1)*elementLength;
				for (int i=0;i<elementLength;i++){
					queue[offset]=element[i];
				}
				tail++;
				if (tail==queueLength){
					tail=0;//重头再来
				}
				return true;
			}
		}
	}

	/**
	 *
	 * @param element to reduce the granularity in synchronized
	 * @return
	 */
	public byte[] remove(byte[] element){
		synchronized (queue){
			if(tail==head+1){
				return null;
			} else {
	//			byte[] element=new byte[elementLength];
				System.arraycopy(queue, head*elementLength,element,0,elementLength);
				head++;
				if (head==queueLength){
					head=0;
				}
				return element;
			}
		}
	}
}
