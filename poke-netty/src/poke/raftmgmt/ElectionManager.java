/*
 * copyright 2014, gash
 * 
 * Gash licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package poke.raftmgmt;

import java.beans.Beans;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.core.Mgmt.LeaderElection;
import poke.core.Mgmt.LeaderElection.ElectAction;
import poke.core.Mgmt.Management;
import poke.core.Mgmt.MgmtHeader;
import poke.core.Mgmt.VectorClock;
import poke.server.conf.ServerConf;
import poke.server.election.Election;
import poke.server.election.ElectionListener;
import poke.server.election.RaftMain;
import poke.server.monitor.HeartMonitor;

/**
 * The election manager is used to determine leadership within the network. The
 * leader is not always a central point in which decisions are passed. For
 * instance, a leader can be used to break ties or act as a scheduling dispatch.
 * However, the dependency on a leader in a decentralized design (or any design,
 * matter of fact) can lead to bottlenecks during heavy, peak loads.
 * 
 * TODO An election is a special case of voting. We should refactor to use only
 * voting.
 * 
 * QUESTIONS:
 * 
 * Can we look to the PAXOS alg. (see PAXOS Made Simple, Lamport, 2001) to model
 * our behavior where we have no single point of failure; each node within the
 * network can act as a consensus requester and coordinator for localized
 * decisions? One can envision this as all nodes accept jobs, and all nodes
 * request from the network whether to accept or reject the request/job.
 * 
 * Does a 'random walk' approach to consistent data replication work?
 * 
 * What use cases do we would want a central coordinator vs. a consensus
 * building model? How does this affect liveliness?
 * 
 * Notes:
 * <ul>
 * <li>Communication: the communication (channel) established by the heartbeat
 * manager is used by the election manager to communicate elections. This
 * creates a constraint that in order for internal (mgmt) tasks to be sent to
 * other nodes, the heartbeat must have already created the channel.
 * </ul>
 * 
 * @author gash
 * 
 */
public class ElectionManager implements ElectionListener {
	protected static Logger logger = LoggerFactory.getLogger("ElectionManager");
	protected static AtomicReference<ElectionManager> instance = new AtomicReference<ElectionManager>();
	private static ServerConf conf;
	
	// number of times we try to get the leader when a node starts up
	private static int firstTime = 2;
	
	public static int getFirstTime() {
		return firstTime;
	}

	public static void setFirstTime(int firstTime) {
		ElectionManager.firstTime = firstTime;
	}

	/** The election that is in progress - only ONE! */
	private Election election;
	private int electionCycle = -1;
	private Integer syncPt = 1;
	private int lastLogIndex = 0;
	private RaftState state = RaftState.Follower;
	LeaderElection req;
	public static boolean canVote = true;

	public enum RaftState {
		Follower, Candidate, Leader
	}
	
	/** The leader */
	static Integer leaderNode;
	private static int leader=-1;

	
	
	public static int getLeader() {
		return leader;
	}

	public static void setLeader(int leader) {
		ElectionManager.leader = leader;
	}

	public static ElectionManager initManager(ServerConf conf) {
		ElectionManager.conf = conf;
		instance.compareAndSet(null, new ElectionManager());
		return instance.get();
	}

	/**
	 * Access a consistent instance for the life of the process.
	 * 
	 * TODO do we need to have a singleton for this? What happens when a process
	 * acts on behalf of separate interests?
	 * 
	 * @return
	 */
	public static ElectionManager getInstance() {
		// TODO throw exception if not initialized!
		return instance.get();
	}
	
//	public void setElectionObject(Election elect) {
//		// TODO Auto-generated method stub
//		electionManager=elect;
//	}
//	
//	public Election getElectionObject() {
//		// TODO Auto-generated method stub
//		return electionManager;
//	}

	/**
	 * returns the leader of the network
	 * 
	 * @return
	 */
	public Integer whoIsTheLeader() {
		return leaderNode;
	}

	/**
	 * initiate an election from within the server - most likely scenario is the
	 * heart beat manager detects a failure of the leader and calls this method.
	 * 
	 * Depending upon the algo. used (bully, flood, lcr, hs) the
	 * manager.startElection() will create the algo class instance and forward
	 * processing to it. If there was an election in progress and the election
	 * ID is not the same, the new election will supplant the current (older)
	 * election. This should only be triggered from nodes that share an edge
	 * with the leader.
	 */
	public void startElection() {
		//logger.info("Start Election");
		Election elect=null;
		
		this.state = RaftState.Candidate;
		
		try
		{
			ElectionManager.setLeader(-1);
			elect=electionInstance();
			electionCycle = elect.createElectionID();
		}
		catch(Exception e)
		{
			logger.info("Exception: "+e);
		}
		/*
		int min=1000, max=5000;
		
		Random rand=new Random();
		int no=rand.nextInt((max - min) + 1) + min;
		logger.info("*********************************************************************Random Number: "+no);
		
		try {
			Thread.sleep(no);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
		//logger.info("################################################ "+conf.getNodeId()+" woke up");

		LeaderElection.Builder elb = LeaderElection.newBuilder();
		elb.setElectId(electionCycle);
		elb.setAction(ElectAction.DECLAREELECTION);
		elb.setDesc("Node " + conf.getNodeId() + " detects no leader. Election!");
		
		logger.info("Setting "+ conf.getNodeId() + " as a candidate.....");
		canVote = false;
		elb.setCandidateId(conf.getNodeId()); // promote self
		elb.setExpires(2 * 60 * 1000 + System.currentTimeMillis()); // 1 minute

		// bias the voting with my number of votes (for algos that use vote
		// counting)

		// TODO use voting int votes = conf.getNumberOfElectionVotes();

		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(conf.getNodeId());
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); // TODO add security

		VectorClock.Builder rpb = VectorClock.newBuilder();
		rpb.setNodeId(conf.getNodeId());
		rpb.setTime(mhb.getTime());
		rpb.setVersion(electionCycle);
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		// now send it out to all my edges
		//logger.info("Election started by node " + conf.getNodeId());
		logger.info("ElectionCycle: "+ electionCycle);
		ConnectionManager.broadcast(mb.build());
	}



	/**
	 * @param args
	 */
	public void processRequest(Management mgmt) {
		
//		logger.info("Election Manager---> processRequest()");
		if (!mgmt.hasElection())
			return;

		req = mgmt.getElection();

		// when a new node joins the network it will want to know who the leader
		// is - we kind of ram this request-response in the process request
		// though there has to be a better place for it
		
		if (req.getAction().getNumber() == LeaderElection.ElectAction.WHOISTHELEADER_VALUE) {
			respondToWhoIsTheLeader(mgmt);
			return;
		} else if (req.getAction().getNumber() == LeaderElection.ElectAction.THELEADERIS_VALUE) {
			logger.info("Node " + conf.getNodeId() + " got an answer on who the leader is. Its Node "
					+ req.getCandidateId());
			leaderNode = req.getCandidateId();
			setLeader(leaderNode);
			this.electionCycle = req.getElectId();
			return;
		}

		// else fall through to an election

		if (req.hasExpires()) {
			long ct = System.currentTimeMillis();
			if (ct > req.getExpires()) {
				// ran out of time so the election is over
				election.clear();
				return;
			}
		}
		
//		logger.info("Election Manager---> Call Raft.process");
		Management rtn = electionInstance().process(mgmt);
//		logger.info("**********************Raft.process() end************************");
		logger.info("rtn: "+rtn);
		
		if (rtn != null)
			ConnectionManager.broadcast(rtn);
	}

	/**
	 * check the health of the leader (usually called after a HB update)
	 * 
	 * @param mgmt
	 */
	public void assessCurrentState(Management mgmt) {
		//logger.info("ElectionManager.assessCurrentState() checking elected leader status");
		
//		try{
//			logger.info("Candidate ID Selected: "+req.getCandidateId());
//		}
//		catch(Exception e)
//		{
//			logger.error("Exception while calling candidate ID: "+e);
//		}
//		logger.info("Node: "+conf.getNodeId()+" accessing the state");

//		logger.info("#Connections:"+ConnectionManager.getNumMgmtConnections()+"**************************************************************************");
		
		int m = (int) (0+Math.random()*1500);
		try {
			logger.info("Sleeping for "+m +" seconds");
			Thread.sleep(m);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if (firstTime > 0 && ConnectionManager.getNumMgmtConnections() > 0) {
			// give it two tries to get the leader
//			logger.info("Node: "+conf.getNodeId()+" asking for "+ (3-firstTime) +" times who is the leader?");
			
			firstTime--;
			askWhoIsTheLeader();
			
			
			
		} else if (leaderNode == null && (election == null || !election.isElectionInprogress())) {
			// if this is not an election state, we need to assess the H&S of
			// the network's leader
			if(this.state!=RaftState.Candidate)
			{
				synchronized (syncPt) {
//					logger.info("Either leaderNode is null or election is null or election is not in progress");			
	//				logger.info("Node: "+conf.getNodeId()+" is starting the election");
					
					//LeaderElection req = mgmt.getElection();
					
					//if(this.electionCycle <= req.getElectId())
					//{
					try{
						logger.info(req.getCandidateId()+ " is the candidate but still it's going to start election");
					}
					catch(Exception e)
					{
						
					}
						startElection();
					//}
				}
			}
		}
	}

	/** election listener implementation */
	@Override
	public void concludeWith(boolean success, Integer leaderID) {
		if (success) {
			logger.info("----> the leader is " + leaderID);
			leaderNode = leaderID;
			setLeader(leaderNode);
		}

		election.clear();
	}

	private void respondToWhoIsTheLeader(Management mgmt) {
		if (leaderNode == null) {
			//logger.info("----> I cannot respond to who the leader is! I don't know!");
//			logger.info("Node: "+conf.getNodeId()+" is saying I cannot respond to who the leader is! I don't know!");
			return;
		}

		logger.info("Node " + conf.getNodeId() + " is replying to " + mgmt.getHeader().getOriginator()
				+ "'s request who the leader is. Its Node " + leaderNode);

		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(conf.getNodeId());
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); // TODO add security

		VectorClock.Builder rpb = VectorClock.newBuilder();
		rpb.setNodeId(conf.getNodeId());
		rpb.setTime(mhb.getTime());
		rpb.setVersion(electionCycle);
		mhb.addPath(rpb);

		LeaderElection.Builder elb = LeaderElection.newBuilder();
		elb.setElectId(electionCycle);
		elb.setAction(ElectAction.THELEADERIS);
		elb.setDesc("Node " + leaderNode + " is the leader");
		elb.setCandidateId(leaderNode);
		elb.setExpires(-1);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		// now send it to the requester
//		logger.info("Leader Selected, now send it to the requester");
		try {

			ConnectionManager.getConnection(mgmt.getHeader().getOriginator(), true).write(mb.build());
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private void askWhoIsTheLeader() {
		//logger.info("Node " + conf.getNodeId() + " is searching for the leader");

		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(conf.getNodeId());
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); // TODO add security

		VectorClock.Builder rpb = VectorClock.newBuilder();
		rpb.setNodeId(conf.getNodeId());
		rpb.setTime(mhb.getTime());
		rpb.setVersion(electionCycle);
		mhb.addPath(rpb);

		LeaderElection.Builder elb = LeaderElection.newBuilder();
		elb.setElectId(-1);
		elb.setAction(ElectAction.WHOISTHELEADER);
		elb.setDesc("Node " + leaderNode + " is asking who the leader is");
		elb.setCandidateId(-1);
		elb.setExpires(-1);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		// now send it to the requester
		ConnectionManager.broadcast(mb.build());
	}

	private Election electionInstance() {
		if (election == null) {
			synchronized (syncPt) {
				if (election !=null)
					return election;
				
				// new election
				String clazz = ElectionManager.conf.getElectionImplementation();
				//logger.info("Election Instance: "+clazz);
				
				// if an election instance already existed, this would
				// override the current election
				try {
					//logger.info("Election Class Loader: "+this.getClass().getClassLoader());
					election=new RaftMain();
				//	election = (Election) Beans.instantiate(this.getClass().getClassLoader(), clazz);
					
					//logger.info("Election Instance 1 for Node ID: "+conf.getNodeId()+" "+election.toString());
					
					election.setNodeId(conf.getNodeId());
					election.setListener(this);

					// this sucks - bad coding here! should use configuration
					// properties
//					if (election instanceof RaftMain) {
//						//logger.warn("## Node " + conf.getNodeId() + " is about to start the election ##");
//						//((RaftAlgorithm) election).setMaxHops(4);
//						//((RaftAlgorithm) election).initialize(HeartbeatManager.liveNodes);
//					}

				} catch (Exception e) {
					logger.error("Failed to create " + clazz, e);
				}
			}
		}

		return election;

	}

	@Override
	public int getElectId() {
		// TODO Auto-generated method stub
		//logger.info("Entered getElectId()");
		return election.getElectionId();
	}

	@Override
	public int getLastLogIndex() {
		// TODO Auto-generated method stub
		//logger.info("Entered getLastLogIndex()");
		return 0;
	}

	@Override
	public void setElectId(int electId)
	{
		this.electionCycle=electId;
	}
	
	@Override
	public RaftState getState(){
		return this.state;
	}
	
	@Override
	public void setState(RaftState state){
		this.state = state;
	}
	
	public void setLastLogIndex(int lastLogIndex)
	{
		this.lastLogIndex=lastLogIndex;
	}
	
	public static void resetLeader(){
		leaderNode=null;
		leader = -1;
	}
}