/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.Record;
import org.apache.jute.BinaryOutputArchive;

import org.apache.zookeeper.proto.*;
import org.apache.zookeeper.txn.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.BadArgumentsException;
import org.apache.zookeeper.MultiTransactionRecord;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.ZooKeeperServer.ChangeRecord;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.quorum.Leader.XidRolloverException;

/**
 * This request processor is generally at the start of a RequestProcessor
 * change. It sets up any transactions associated with requests that change the
 * state of the system. It counts on ZooKeeperServer to update
 * outstandingRequests, so that it can take into account transactions that are
 * in the queue to be applied when generating a transaction.
 */
public class PrepRequestProcessor extends Thread implements RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(PrepRequestProcessor.class);

    static boolean skipACL;
    static {
        skipACL = System.getProperty("zookeeper.skipACL", "no").equals("yes");
        if (skipACL) {
            LOG.info("zookeeper.skipACL==\"yes\", ACL checks will be skipped");
        }
    }

    /**
     * this is only for testing purposes.
     * should never be useed otherwise
     */
    private static  boolean failCreate = false;

    LinkedBlockingQueue<Request> submittedRequests = new LinkedBlockingQueue<Request>();

    private final RequestProcessor nextProcessor;

    ZooKeeperServer zks;

    public PrepRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super("ProcessThread(sid:" + zks.getServerId()
                + " cport:" + zks.getClientPort() + "):");
        this.nextProcessor = nextProcessor;
        this.zks = zks;
    }

    /**
     * method for tests to set failCreate
     * @param b
     */
    public static void setFailCreate(boolean b) {
        failCreate = b;
    }
    @Override
    public void run() {
        try {
            while (true) {
                Request request = submittedRequests.take();
                long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
                if (request.type == OpCode.ping) {
                    traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;
                }
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logRequest(LOG, traceMask, 'P', request, "");
                }
                if (Request.requestOfDeath == request) {
                    break;
                }
                pRequest(request);
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        } catch (RequestProcessorException e) {
            if (e.getCause() instanceof XidRolloverException) {
                LOG.info(e.getCause().getMessage());
            }
            LOG.error("Unexpected exception", e);
        } catch (Exception e) {
            LOG.error("Unexpected exception", e);
        }
        LOG.info("PrepRequestProcessor exited loop!");
    }

    private ChangeRecord getRecordForPath(String path) throws KeeperException.NoNodeException {
        ChangeRecord lastChange = null;
        synchronized (zks.outstandingChanges) {
            lastChange = zks.outstandingChangesForPath.get(path);
            /*
            for (int i = 0; i < zks.outstandingChanges.size(); i++) {
                ChangeRecord c = zks.outstandingChanges.get(i);
                if (c.path.equals(path)) {
                    lastChange = c;
                }
            }
            */
            if (lastChange == null) {
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n != null) {
                    Long acl;
                    Set<String> children;
                    synchronized(n) {
                        acl = n.acl;
                        children = n.getChildren();
                    }
                    lastChange = new ChangeRecord(-1, path, n.stat,
                        children != null ? children.size() : 0,
                            zks.getZKDatabase().convertLong(acl));
                }
            }
        }
        if (lastChange == null || lastChange.stat == null) {
            throw new KeeperException.NoNodeException(path);
        }
        return lastChange;
    }

    private void addChangeRecord(ChangeRecord c) {
        synchronized (zks.outstandingChanges) {
            zks.outstandingChanges.add(c);
            zks.outstandingChangesForPath.put(c.path, c);
        }
    }

    /**
     * Grab current pending change records for each op in a multi-op.
     *
     * This is used inside MultiOp error code path to rollback in the event
     * of a failed multi-op.
     *
     * @param multiRequest
     */
    private Map<String, ChangeRecord> getPendingChanges(MultiTransactionRecord multiRequest) {
        HashMap<String, ChangeRecord> pendingChangeRecords = new HashMap<String, ChangeRecord>();

        for(Op op: multiRequest) {
            String path = op.getPath();

            try {
                ChangeRecord cr = getRecordForPath(path);
                if (cr != null) {
                    pendingChangeRecords.put(path, cr);
                }

                /*
                 * ZOOKEEPER-1624 - We need to store for parent's ChangeRecord
                 * of the parent node of a request. So that if this is a
                 * sequential node creation request, rollbackPendingChanges()
                 * can restore previous parent's ChangeRecord correctly.
                 *
                 * Otherwise, sequential node name generation will be incorrect
                 * for a subsequent request.
                 */
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1) {
                    continue;
                }
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentCr = getRecordForPath(parentPath);
                if (parentCr != null) {
                    pendingChangeRecords.put(parentPath, parentCr);
                }
            } catch (KeeperException.NoNodeException e) {
                // ignore this one
            }
        }

        return pendingChangeRecords;
    }

    /**
     * Rollback pending changes records from a failed multi-op.
     *
     * If a multi-op fails, we can't leave any invalid change records we created
     * around. We also need to restore their prior value (if any) if their prior
     * value is still valid.
     *
     * @param zxid
     * @param pendingChangeRecords
     */
    void rollbackPendingChanges(long zxid, Map<String, ChangeRecord>pendingChangeRecords) {

        synchronized (zks.outstandingChanges) {
            // Grab a list iterator starting at the END of the list so we can iterate in reverse
            ListIterator<ChangeRecord> iter = zks.outstandingChanges.listIterator(zks.outstandingChanges.size());
            while (iter.hasPrevious()) {
                ChangeRecord c = iter.previous();
                if (c.zxid == zxid) {
                    iter.remove();
                    zks.outstandingChangesForPath.remove(c.path);
                } else {
                    break;
                }
            }

            boolean empty = zks.outstandingChanges.isEmpty();
            long firstZxid = 0;
            if (!empty) {
                firstZxid = zks.outstandingChanges.get(0).zxid;
            }

            Iterator<ChangeRecord> priorIter = pendingChangeRecords.values().iterator();
            while (priorIter.hasNext()) {
                ChangeRecord c = priorIter.next();

                /* Don't apply any prior change records less than firstZxid */
                if (!empty && (c.zxid < firstZxid)) {
                    continue;
                }

                zks.outstandingChangesForPath.put(c.path, c);
            }
        }
    }

    /**
     * Grant or deny authorization to an operation on a node as a function of:
     *
     * @param zks: not used.
     * @param acl:  set of ACLs for the node
     * @param perm: the permission that the client is requesting
     * @param ids:  the credentials supplied by the client
     */
    static void checkACL(ZooKeeperServer zks, List<ACL> acl, int perm,
            List<Id> ids) throws KeeperException.NoAuthException {
        if (skipACL) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Permission requested: {} ", perm);
            LOG.debug("ACLs for node: {}", acl);
            LOG.debug("Client credentials: {}", ids);
        }
        if (acl == null || acl.size() == 0) {
            return;
        }
        for (Id authId : ids) {
            if (authId.getScheme().equals("super")) {
                return;
            }
        }
        for (ACL a : acl) {
            Id id = a.getId();
            if ((a.getPerms() & perm) != 0) {
                if (id.getScheme().equals("world")
                        && id.getId().equals("anyone")) {
                    return;
                }
                AuthenticationProvider ap = ProviderRegistry.getProvider(id
                        .getScheme());
                if (ap != null) {
                    for (Id authId : ids) {
                        if (authId.getScheme().equals(id.getScheme())
                                && ap.matches(authId.getId(), id.getId())) {
                            return;
                        }
                    }
                }
            }
        }
        throw new KeeperException.NoAuthException();
    }

    /**
     * Performs basic validation of a path for a create request.
     * Throws if the path is not valid and returns the parent path.
     * @throws BadArgumentsException
     */
    private String validatePathForCreate(String path, long sessionId)
            throws BadArgumentsException {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1 || path.indexOf('\0') != -1 || failCreate) {
            LOG.info("Invalid path " + path + " with session 0x" +
                    Long.toHexString(sessionId));
            throw new KeeperException.BadArgumentsException(path);
        }
        return path.substring(0, lastSlash);
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param type
     * @param zxid
     * @param request
     * @param record
     */
    protected void pRequest2Txn(int type, long zxid, Request request,
                                Record record, boolean deserialize)
        throws KeeperException, IOException, RequestProcessorException
    {
        request.setHdr(new TxnHeader(request.sessionId, request.cxid, zxid, zks.getTime(), type));

        switch (type) {
            case OpCode.create: {
                CreateRequest createRequest = (CreateRequest)record;
                if (deserialize) {
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                }
                CreateMode createMode = CreateMode.fromFlag(createRequest.getFlags());
                validateCreateRequest(createMode, request);
                String path = createRequest.getPath();
                String parentPath = validatePathForCreate(path, request.sessionId);

                List<ACL> listACL = removeDuplicates(createRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE, request.authInfo);
                int parentCVersion = parentRecord.stat.getCversion();
                if (createMode.isSequential()) {
                    path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
                }
                try {
                    PathUtils.validatePath(path);
                } catch(IllegalArgumentException ie) {
                    LOG.info("Invalid path " + path + " with session 0x" +
                            Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException(path);
                    }
                } catch (KeeperException.NoNodeException e) {
                    // ignore this one
                }
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException(path);
                }
                int newCversion = parentRecord.stat.getCversion()+1;
                request.setTxn(new CreateTxn(path, createRequest.getData(), listACL, createMode.isEphemeral(),
                        newCversion));
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }
                parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
                parentRecord.childCount++;
                parentRecord.stat.setCversion(newCversion);
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.getHdr().getZxid(), path, s, 0, listACL));
                break;
            }
            case OpCode.create2: {
                Create2Request createRequest = (Create2Request)record;
                if (deserialize) {
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                }
                CreateMode createMode = CreateMode.fromFlag(createRequest.getFlags());
                validateCreateRequest(createMode, request);
                String path = createRequest.getPath();
                String parentPath = validatePathForCreate(path, request.sessionId);

                List<ACL> listACL = removeDuplicates(createRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE, request.authInfo);
                int parentCVersion = parentRecord.stat.getCversion();
                if (createMode.isSequential()) {
                    path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
                }
                try {
                    PathUtils.validatePath(path);
                } catch(IllegalArgumentException ie) {
                    LOG.info("Invalid path " + path + " with session 0x" +
                            Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException(path);
                    }
                } catch (KeeperException.NoNodeException e) {
                    // ignore this one
                }
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException(path);
                }
                int newCversion = parentRecord.stat.getCversion()+1;
                request.setTxn(new CreateTxn(path, createRequest.getData(), listACL, createMode.isEphemeral(),
                        newCversion));
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }
                parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
                parentRecord.childCount++;
                parentRecord.stat.setCversion(newCversion);
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.getHdr().getZxid(), path, s, 0, listACL));
                break;
            }
            case OpCode.delete:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                DeleteRequest deleteRequest = (DeleteRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, deleteRequest);
                String path = deleteRequest.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1
                        || zks.getZKDatabase().isSpecialPath(path)) {
                    throw new KeeperException.BadArgumentsException(path);
                }
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentRecord = getRecordForPath(parentPath);
                ChangeRecord nodeRecord = getRecordForPath(path);
                checkACL(zks, parentRecord.acl, ZooDefs.Perms.DELETE, request.authInfo);
                checkAndIncVersion(nodeRecord.stat.getVersion(), deleteRequest.getVersion(), path);
                if (nodeRecord.childCount > 0) {
                    throw new KeeperException.NotEmptyException(path);
                }
                request.setTxn(new DeleteTxn(path));
                parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
                parentRecord.childCount--;
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.getHdr().getZxid(), path, null, -1, null));
                break;
	        case OpCode.allocate:{
		        AllocateRequest allocateRequest = (AllocateRequest)record;
		        if (deserialize) {
			        ByteBufferInputStream.byteBuffer2Record(request.request, allocateRequest);
		        }
		        path = allocateRequest.getPath();
		        nodeRecord = getRecordForPath(path);
		        checkACL(zks, nodeRecord.acl, ZooDefs.Perms.WRITE, request.authInfo);
		        int newVersion = checkAndIncVersion(nodeRecord.stat.getVersion(), allocateRequest.getVersion(), path);
		        request.setTxn(new AllocateTxn(path, allocateRequest.getData(), newVersion));
		        nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
		        nodeRecord.stat.setVersion(newVersion);
		        addChangeRecord(nodeRecord);
		        break;
	        }
            case OpCode.setData:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                SetDataRequest setDataRequest = (SetDataRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, setDataRequest);
                path = setDataRequest.getPath();
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.WRITE, request.authInfo);
                int newVersion = checkAndIncVersion(nodeRecord.stat.getVersion(), setDataRequest.getVersion(), path);
                request.setTxn(new SetDataTxn(path, setDataRequest.getData(), newVersion));
                nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
                nodeRecord.stat.setVersion(newVersion);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.reconfig:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                ReconfigRequest reconfigRequest = (ReconfigRequest)record;                             
                LeaderZooKeeperServer lzks = (LeaderZooKeeperServer)zks;
                QuorumVerifier lastSeenQV = lzks.self.getLastSeenQuorumVerifier();                                                                                 
                // check that there's no reconfig in progress
                if (lastSeenQV.getVersion()!=lzks.self.getQuorumVerifier().getVersion()) {
                       throw new KeeperException.ReconfigInProgress(); 
                }
                long configId = reconfigRequest.getCurConfigId();
  
                if (configId != -1 && configId!=lzks.self.getLastSeenQuorumVerifier().getVersion()){
                   String msg = "Reconfiguration from version " + configId + " failed -- last seen version is " + lzks.self.getLastSeenQuorumVerifier().getVersion();
                   throw new KeeperException.BadVersionException(msg);
                }

                String newMembers = reconfigRequest.getNewMembers();
                
                if (newMembers != null) { //non-incremental membership change                  
                   LOG.info("Non-incremental reconfig");
                
                   // Input may be delimited by either commas or newlines so convert to common newline separated format
                   newMembers = newMembers.replaceAll(",", "\n");
                   
                   try{
                       Properties props = new Properties();                        
                       props.load(new StringReader(newMembers));
                       QuorumPeerConfig config = new QuorumPeerConfig();
                       config.parseDynamicConfig(props, lzks.self.getElectionType(), true);
                       request.qv = config.getQuorumVerifier();
                       request.qv.setVersion(request.getHdr().getZxid());
                   } catch (IOException e) {
                       throw new KeeperException.BadArgumentsException(e.getMessage());
                   } catch (ConfigException e) {
                       throw new KeeperException.BadArgumentsException(e.getMessage());
                   }                   
                } else { //incremental change - must be a majority quorum system   
                   LOG.info("Incremental reconfig");
                   
                   List<String> joiningServers = null; 
                   String joiningServersString = reconfigRequest.getJoiningServers();
                   if (joiningServersString != null)
                   {
                       joiningServers = StringUtils.split(joiningServersString,",");
                   }
                   
                   List<String> leavingServers = null;
                   String leavingServersString = reconfigRequest.getLeavingServers();
                   if (leavingServersString != null)
                   {
                       leavingServers = StringUtils.split(leavingServersString, ",");
                   }
                   
                   if (!(lastSeenQV instanceof QuorumMaj)) {
                           String msg = "Incremental reconfiguration requested but last configuration seen has a non-majority quorum system";
                           LOG.warn(msg);
                           throw new KeeperException.BadArgumentsException(msg);               
                   }
                   Map<Long, QuorumServer> nextServers = new HashMap<Long, QuorumServer>(lastSeenQV.getAllMembers());
                   try {                           
                       if (leavingServers != null) {
                           for (String leaving: leavingServers){
                               long sid = Long.parseLong(leaving);
                               nextServers.remove(sid);
                           } 
                       }
                       if (joiningServers != null) {
                           for (String joiner: joiningServers){
                        	   // joiner should have the following format: server.x = server_spec;client_spec               
                        	   String[] parts = StringUtils.split(joiner, "=").toArray(new String[0]);
                               if (parts.length != 2) {
                                   throw new KeeperException.BadArgumentsException("Wrong format of server string");
                               }
                               // extract server id x from first part of joiner: server.x
                               Long sid = Long.parseLong(parts[0].substring(parts[0].lastIndexOf('.') + 1));
                               QuorumServer qs = new QuorumServer(sid, parts[1]);
                               if (qs.clientAddr == null || qs.electionAddr == null || qs.addr == null) {
                                   throw new KeeperException.BadArgumentsException("Wrong format of server string - each server should have 3 ports specified"); 	   
                               }
                               nextServers.remove(qs.id);
                               nextServers.put(Long.valueOf(qs.id), qs);
                           }  
                       }
                   } catch (ConfigException e){
                       throw new KeeperException.BadArgumentsException("Reconfiguration failed");
                   }
                   request.qv = new QuorumMaj(nextServers);
                   request.qv.setVersion(request.getHdr().getZxid());
                }                             
                if (request.qv.getVotingMembers().size() < 2){
                   String msg = "Reconfig failed - new configuration must include at least 2 followers";
                   LOG.warn(msg);
                   throw new KeeperException.BadArgumentsException(msg);
               }
                   
                if (!lzks.getLeader().isQuorumSynced(request.qv)) {
                   String msg2 = "Reconfig failed - there must be a connected and synced quorum in new configuration";
                   LOG.warn(msg2);             
                   throw new KeeperException.NewConfigNoQuorum();
                }
                
                nodeRecord = getRecordForPath(ZooDefs.CONFIG_NODE);               
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.WRITE, request.authInfo);                  
                request.setTxn(new SetDataTxn(ZooDefs.CONFIG_NODE, request.qv.toString().getBytes(), -1));    
                nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
                nodeRecord.stat.setVersion(-1);                
                addChangeRecord(nodeRecord);
                break;                         
            case OpCode.setACL:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                SetACLRequest setAclRequest = (SetACLRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, setAclRequest);
                path = setAclRequest.getPath();
                List<ACL> listACL = removeDuplicates(setAclRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.ADMIN, request.authInfo);
                newVersion = checkAndIncVersion(nodeRecord.stat.getAversion(), setAclRequest.getVersion(), path);
                request.setTxn(new SetACLTxn(path, listACL, newVersion));
                nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
                nodeRecord.stat.setAversion(newVersion);
                addChangeRecord(nodeRecord);
                break;
            case OpCode.createSession:
                request.request.rewind();
                int to = request.request.getInt();
                request.setTxn(new CreateSessionTxn(to));
                request.request.rewind();
                if (request.isLocalSession()) {
                    // This will add to local session tracker if it is enabled
                    zks.sessionTracker.addSession(request.sessionId, to);
                } else {
                    // Explicitly add to global session if the flag is not set
                    zks.sessionTracker.addGlobalSession(request.sessionId, to);
                }
                zks.setOwner(request.sessionId, request.getOwner());
                break;
            case OpCode.closeSession:
                // We don't want to do this check since the session expiration thread
                // queues up this operation without being the session owner.
                // this request is the last of the session so it should be ok
                //zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                Set<String> es = zks.getZKDatabase()
                        .getEphemerals(request.sessionId);
                synchronized (zks.outstandingChanges) {
                    for (ChangeRecord c : zks.outstandingChanges) {
                        if (c.stat == null) {
                            // Doing a delete
                            es.remove(c.path);
                        } else if (c.stat.getEphemeralOwner() == request.sessionId) {
                            es.add(c.path);
                        }
                    }
                    for (String path2Delete : es) {
                        addChangeRecord(new ChangeRecord(request.getHdr().getZxid(), path2Delete, null, 0, null));
                    }

                    zks.sessionTracker.setSessionClosing(request.sessionId);
                }

                LOG.info("Processed session termination for sessionid: 0x"
                        + Long.toHexString(request.sessionId));
                break;
            case OpCode.check:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                CheckVersionRequest checkVersionRequest = (CheckVersionRequest)record;
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, checkVersionRequest);
                path = checkVersionRequest.getPath();
                nodeRecord = getRecordForPath(path);
                checkACL(zks, nodeRecord.acl, ZooDefs.Perms.READ, request.authInfo);
                request.setTxn(new CheckVersionTxn(path, checkAndIncVersion(nodeRecord.stat.getVersion(),
                        checkVersionRequest.getVersion(), path)));
                break;
        }
    }

    private static int checkAndIncVersion(int currentVersion, int expectedVersion, String path)
            throws KeeperException.BadVersionException {
        if (expectedVersion != -1 && expectedVersion != currentVersion) {
            throw new KeeperException.BadVersionException(path);
        }
        return currentVersion + 1;
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param request
     */
    protected void pRequest(Request request) throws RequestProcessorException {
        // LOG.info("Prep>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = 0x" + Long.toHexString(request.sessionId));
        request.setHdr(null);
        request.setTxn(null);

        try {
            switch (request.type) {
            case OpCode.allocate:
	            AllocateRequest allocateRequest = new AllocateRequest();
	            pRequest2Txn(request.type, zks.getNextZxid(), request, allocateRequest, true);
	            break;
            case OpCode.create:
                CreateRequest createRequest = new CreateRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, createRequest, true);
                break;
            case OpCode.create2:
                Create2Request create2Request = new Create2Request();
                pRequest2Txn(request.type, zks.getNextZxid(), request, create2Request, true);
                break;
            case OpCode.delete:
                DeleteRequest deleteRequest = new DeleteRequest();               
                pRequest2Txn(request.type, zks.getNextZxid(), request, deleteRequest, true);
                break;
            case OpCode.setData:
                SetDataRequest setDataRequest = new SetDataRequest();                
                pRequest2Txn(request.type, zks.getNextZxid(), request, setDataRequest, true);
                break;
            case OpCode.reconfig:
                ReconfigRequest reconfigRequest = new ReconfigRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, reconfigRequest);
                pRequest2Txn(request.type, zks.getNextZxid(), request, reconfigRequest, true);
                break;
            case OpCode.setACL:
                SetACLRequest setAclRequest = new SetACLRequest();                
                pRequest2Txn(request.type, zks.getNextZxid(), request, setAclRequest, true);
                break;
            case OpCode.check:
                CheckVersionRequest checkRequest = new CheckVersionRequest();              
                pRequest2Txn(request.type, zks.getNextZxid(), request, checkRequest, true);
                break;
            case OpCode.multi:
                MultiTransactionRecord multiRequest = new MultiTransactionRecord();
                try {
                    ByteBufferInputStream.byteBuffer2Record(request.request, multiRequest);
                } catch(IOException e) {
                    request.setHdr(new TxnHeader(request.sessionId, request.cxid, zks.getNextZxid(),
                                                 zks.getTime(), OpCode.multi));
                   throw e;
                }
                List<Txn> txns = new ArrayList<Txn>();
                //Each op in a multi-op must have the same zxid!
                long zxid = zks.getNextZxid();
                KeeperException ke = null;

                //Store off current pending change records in case we need to rollback
                Map<String, ChangeRecord> pendingChanges = getPendingChanges(multiRequest);

                for(Op op: multiRequest) {
                    Record subrequest = op.toRequestRecord();
                    int type;
                    Record txn;

                    /* If we've already failed one of the ops, don't bother
                     * trying the rest as we know it's going to fail and it
                     * would be confusing in the logfiles.
                     */
                    if (ke != null) {
                        type = OpCode.error;
                        txn = new ErrorTxn(Code.RUNTIMEINCONSISTENCY.intValue());
                    }

                    /* Prep the request and convert to a Txn */
                    else {
                        try {
                            pRequest2Txn(op.getType(), zxid, request, subrequest, false);
                            type = request.getHdr().getType();
                            txn = request.getTxn();
                        } catch (KeeperException e) {
                            ke = e;
                            type = OpCode.error;
                            txn = new ErrorTxn(e.code().intValue());

                            LOG.info("Got user-level KeeperException when processing "
                                    + request.toString() + " aborting remaining multi ops."
                                    + " Error Path:" + e.getPath()
                                    + " Error:" + e.getMessage());

                            request.setException(e);

                            /* Rollback change records from failed multi-op */
                            rollbackPendingChanges(zxid, pendingChanges);
                        }
                    }

                    //FIXME: I don't want to have to serialize it here and then
                    //       immediately deserialize in next processor. But I'm
                    //       not sure how else to get the txn stored into our list.
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
                    txn.serialize(boa, "request") ;
                    ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

                    txns.add(new Txn(type, bb.array()));
                }

                request.setHdr(new TxnHeader(request.sessionId, request.cxid, zxid, zks.getTime(), request.type));
                request.setTxn(new MultiTxn(txns));

                break;

            //create/close session don't require request record
            case OpCode.createSession:
            case OpCode.closeSession:
                if (!request.isLocalSession()) {
                    pRequest2Txn(request.type, zks.getNextZxid(), request,
                                 null, true);
                }
                break;

            //All the rest don't need to create a Txn - just verify session
            case OpCode.sync:
            case OpCode.exists:
            case OpCode.getData:
            case OpCode.getACL:
            case OpCode.getChildren:
            case OpCode.getChildren2:
            case OpCode.ping:
            case OpCode.setWatches:
                zks.sessionTracker.checkSession(request.sessionId,
                        request.getOwner());
                break;
            }
        } catch (KeeperException e) {
            if (request.getHdr() != null) {
                request.getHdr().setType(OpCode.error);
                request.setTxn(new ErrorTxn(e.code().intValue()));
            }
            LOG.info("Got user-level KeeperException when processing "
                    + request.toString()
                    + " Error Path:" + e.getPath()
                    + " Error:" + e.getMessage());
            request.setException(e);
        } catch (Exception e) {
            // log at error level as we are returning a marshalling
            // error to the user
            LOG.error("Failed to process " + request, e);

            StringBuilder sb = new StringBuilder();
            ByteBuffer bb = request.request;
            if(bb != null){
                bb.rewind();
                while (bb.hasRemaining()) {
                    sb.append(Integer.toHexString(bb.get() & 0xff));
                }
            } else {
                sb.append("request buffer is null");
            }

            LOG.error("Dumping request buffer: 0x" + sb.toString());
            if (request.getHdr() != null) {
                request.getHdr().setType(OpCode.error);
                request.setTxn(new ErrorTxn(Code.MARSHALLINGERROR.intValue()));
            }
        }
        request.zxid = zks.getZxid();
        nextProcessor.processRequest(request);
    }

    private List<ACL> removeDuplicates(List<ACL> acl) {

        ArrayList<ACL> retval = new ArrayList<ACL>();
        Iterator<ACL> it = acl.iterator();
        while (it.hasNext()) {
            ACL a = it.next();
            if (retval.contains(a) == false) {
                retval.add(a);
            }
        }
        return retval;
    }
    
    private void validateCreateRequest(CreateMode createMode, Request request)
            throws KeeperException {
        if (createMode.isEphemeral()) {
            // Exception is set when local session failed to upgrade
            // so we just need to report the error
            if (request.getException() != null) {
                throw request.getException();
            }
            zks.sessionTracker.checkGlobalSession(request.sessionId,
                    request.getOwner());
        } else {
            zks.sessionTracker.checkSession(request.sessionId,
                    request.getOwner());
        }
    }

    /**
     * This method checks out the acl making sure it isn't null or empty,
     * it has valid schemes and ids, and expanding any relative ids that
     * depend on the requestor's authentication information.
     *
     * @param authInfo list of ACL IDs associated with the client connection
     * @param acl list of ACLs being assigned to the node (create or setACL operation)
     * @return
     */
    private boolean fixupACL(List<Id> authInfo, List<ACL> acl) {
        if (skipACL) {
            return true;
        }
        if (acl == null || acl.size() == 0) {
            return false;
        }

        Iterator<ACL> it = acl.iterator();
        LinkedList<ACL> toAdd = null;
        while (it.hasNext()) {
            ACL a = it.next();
            Id id = a.getId();
            if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                // wide open
            } else if (id.getScheme().equals("auth")) {
                // This is the "auth" id, so we have to expand it to the
                // authenticated ids of the requestor
                it.remove();
                if (toAdd == null) {
                    toAdd = new LinkedList<ACL>();
                }
                boolean authIdValid = false;
                for (Id cid : authInfo) {
                    AuthenticationProvider ap =
                        ProviderRegistry.getProvider(cid.getScheme());
                    if (ap == null) {
                        LOG.error("Missing AuthenticationProvider for "
                                + cid.getScheme());
                    } else if (ap.isAuthenticated()) {
                        authIdValid = true;
                        toAdd.add(new ACL(a.getPerms(), cid));
                    }
                }
                if (!authIdValid) {
                    return false;
                }
            } else {
                AuthenticationProvider ap = ProviderRegistry.getProvider(id.getScheme());
                if (ap == null) {
                    return false;
                }
                if (!ap.isValid(id.getId())) {
                    return false;
                }
            }
        }
        if (toAdd != null) {
            for (ACL a : toAdd) {
                acl.add(a);
            }
        }
        return acl.size() > 0;
    }

    public void processRequest(Request request) {
        submittedRequests.add(request);
    }

    public void shutdown() {
        LOG.info("Shutting down");
        submittedRequests.clear();
        submittedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }
}
