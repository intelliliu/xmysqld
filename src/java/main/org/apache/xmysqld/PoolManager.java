package org.apache.xmysqld;

import org.apache.zookeeper.server.quorum.QuorumPeerMain;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created with IntelliJ IDEA.
 * User: ye
 * Date: 13-12-1
 * Time: 下午2:52
 * To change this template use File | Settings | File Templates.
 */
public class PoolManager {
	static {
		String[] args={"/etc/zoo.cfg"};
		QuorumPeerMain.main(args);
	}

	private static ConcurrentHashMap<String,AutoIncrementIDPool> poolManager;

	public static void register(String path, AutoIncrementIDPool pool){
		poolManager.put(path, pool);
	}

	public static AutoIncrementIDPool lookup(String path){
		return poolManager.get(path);
	}

	public static void main(String[] args){
		long pk=PoolManager.lookup("/mysql_id/hnair/tb_order/id").getId();
	}
}
