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
package poke.server.election;

import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.core.Mgmt.LeaderElection;
import poke.core.Mgmt.LeaderElection.ElectAction;
import poke.core.Mgmt.Management;
import poke.core.Mgmt.MgmtHeader;
import poke.core.Mgmt.VectorClock;
import poke.raftmgmt.ConnectionManager;
import poke.raftmgmt.ElectionManager;
import poke.raftmgmt.ElectionManager.RaftState;

//import poke.server.election.RaftMain1.Role;

/**
 * 
 * @author gash
 * 
 */
public class RaftMain implements Election {
	protected static Logger logger = LoggerFactory.getLogger("Raft");
	private Integer nodeId;
	private ElectionState current;
	private int voteCount=1;
	private ElectionListener listener;
	private long startTime, endTime=0;
	private Election election=null;

	public RaftMain() {
	}

	/**
	 * init with whoami
	 * 
	 * @param nodeId
	 */
	public RaftMain(Integer nodeId) {
		this.nodeId = nodeId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.election.Election#setListener(poke.server.election.
	 * ElectionListener)
	 */
	@Override
	public void setListener(ElectionListener listener) {
		this.listener = listener;
	}
	

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.election.Election#process(eye.Comm.LeaderElection)
	 * 
	 * @return The Management instance returned represents the message to send
	 * on. If null, no action is required. Not a great choice to convey to the
	 * caller; can be improved upon easily.
	 */
	@Override
	public Management process(Management mgmt) {
		
		if (!mgmt.hasElection())
			return null;

		LeaderElection req = mgmt.getElection();
		if (req.getExpires() <= System.currentTimeMillis()) {
			// election has expired without a conclusion?
		}
		
		Management rtn = null;
		
		if (req.getAction().getNumber() == ElectAction.DECLAREELECTION_VALUE) {
			logger.info("An election is declared!");

			startTime=System.currentTimeMillis();

			// required to eliminate duplicate messages - on a declaration,
			// should not happen if the network does not have cycles
			List<VectorClock> rtes = mgmt.getHeader().getPathList();
			for (VectorClock rp : rtes) {
				if (rp.getNodeId() == this.nodeId) {
					// message has already been sent to me, don't use and
					// forward
					return null;
				}
			}
			// I got here because the election is unknown to me
			
			// this 'if debug is on' should cover the below dozen or so
			// println()s. It is here to help with seeing when an election
			// occurs.
			if (logger.isDebugEnabled()) {
			}
			
			System.out.println("\n\n*********************************************************");
			System.out.println(" RAFT ELECTION: Election declared");
			System.out.println("   Election ID:  " + req.getElectId());
			System.out.println("   Last Log Index:  " + req.getLastLogIndex());
			System.out.println("   Rcv from:     Node " + mgmt.getHeader().getOriginator());
			System.out.println("   Expires:      " + new Date(req.getExpires()));
			System.out.println("   Nominates:    Node " + req.getCandidateId());
			System.out.println("   Desc:         " + req.getDesc());
			System.out.print("   Routing tbl:  [");
			for (VectorClock rp : rtes)
				System.out.print("Node " + rp.getNodeId() + " (" + rp.getVersion() + "," + rp.getTime() + "), ");
			System.out.println("]");
			System.out.println("*********************************************************\n\n");

			// sync master IDs to current election
			//logger.info("*************ElectionIDGenerator******************");
			//ElectionIDGenerator.setMasterID(req.getElectId());
			//storeCandidateInfo(req.getElectId());
			
			//Send vote request msgs to all nodes and start the election timer
		    
		    //GeneratedMessage msg = HeartbeatManager.getInstance().getGeneratedHB();
//		    scheduleNextElectionTimeout();
		    
		    //start the timer and wait for replies from the other nodes
		    //scheduleTimer(5);
		    
			/**
			 * a new election can be declared over an existing election.
			 * 
			 * TODO need to have an monotonically increasing ID that we can test
			 */
			//logger.info("*************Inside Update current******************Node: "+this.nodeId);
			boolean isNew = updateCurrent(req);
			//logger.info("*************End Update current******************"+ isNew);
			//logger.info("$$$$$$$$$$$$$$$$$$$$$$$$$ Request Vote $$$$$$$$$$$$$$$$$$$$$$$$$$$Node: "+this.nodeId);
			
			rtn = castVote(mgmt, isNew);
			//do something
		}
		
		else if (req.getAction().getNumber() == ElectAction.DECLAREVOID_VALUE)
		{
			// no one was elected, I am dropping into standby mode
			logger.info("TODO: no one was elected, I am dropping into standby mode");
			this.clear();
			notify(false, null);
		}
		
		else if (req.getAction().getNumber() == ElectAction.DECLAREWINNER_VALUE)
		{
			// some node declared itself the leader
			logger.info("Election " + req.getElectId() + ": Node " + req.getCandidateId() + " is declared the leader");
			updateCurrent(mgmt.getElection());
			listener.setState(RaftState.Follower);
			current.active = false; // it's over
			notify(true, req.getCandidateId());
		}
		
		else if (req.getAction().getNumber() == ElectAction.ABSTAIN_VALUE)
		{
			// for some reason, a node declines to vote - therefore, do nothing
			logger.info("Abstained");
		}
		
		else if (req.getAction().getNumber() == ElectAction.NOMINATE_VALUE)
		{
			if(req.getCandidateId() == this.nodeId)// && ElectionManager.getLeader()==-1
			{
				voteCount++;
				logger.info("Votes: "+voteCount);
				if(voteCount >=(  (  (ConnectionManager.getNumMgmtConnections()+1)  /2)  +1) ){
					endTime=System.currentTimeMillis();
					logger.info("Diff time: "+ (endTime-startTime));
					
//					if(endTime-startTime <=1000)
//					{
						ElectionManager.setLeader(this.nodeId);
						rtn = declareWinner(mgmt);
						notify(true, this.nodeId);

						listener.setState(RaftState.Leader);
						//ElectionIDGenerator.setMasterID(req.getElectId());
						this.clear();
						voteCount = 1;
//					}
//					else
//					{
//						voteCount = 1;
//						try
//						{
//							System.out.println("Restarting because of reply votes timeout-->"+ restartElection(mgmt));
//						}
//						catch(Exception e)
//						{
//							System.out.println("Exception while restarting: "+e);
//						}
//					}
				}
			}
			else
			{
//				if(ElectionManager.getLeader()==-1)
//				{
//					logger.info("Stupid came here...casting vote to remain in NOMINATE");
//					updateCurrent(mgmt.getElection());
//					current.active = false; // it's over
//					notify(true, req.getCandidateId());
//					//boolean isNew = updateCurrent(req);
//					//rtn = castVote(mgmt, isNew);
//				}
			}
		}
		else
		{
			// this is me!
			logger.info("**********************************Leader is already elected*******************************");
		}
		return rtn;
	}

private Management restartElection(Management mgmt)
{
	// TODO Auto-generated method stub	
	LeaderElection req = mgmt.getElection();
	LeaderElection.Builder elb = LeaderElection.newBuilder();
	MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
	mhb.setTime(System.currentTimeMillis());
	mhb.setSecurityCode(-999); // TODO add security
	mhb.setOriginator(mgmt.getHeader().getOriginator());
	elb.setElectId(req.getElectId());
	elb.setLastLogIndex(req.getLastLogIndex());
	elb.setAction(ElectAction.DECLAREELECTION);
	elb.setDesc(req.getDesc());
	elb.setExpires(req.getExpires());
	elb.setCandidateId(req.getCandidateId());

//	// my vote
//	if (req.getCandidateId() == this.nodeId) {
//		// if I am not in the list and the candidate is myself, I can
//		// declare myself to be the leader.
//		//
//		// this is non-deterministic as it assumes the message has
//		// reached all nodes in the network (because we know the
//		// diameter or the number of nodes).
//		//
//		// can end up with a partitioned graph of leaders if hops <
//		// diameter!
//
//		// this notify goes out to on-node listeners and will arrive before
//		// the other nodes receive notice.
//		notify(true, this.nodeId);
//
//		elb.setAction(ElectAction.DECLAREWINNER);
//		elb.setHops(mgmt.getHeader().getPathCount());
//		logger.info("Node " + this.nodeId + " is declaring itself the leader");
//	} else {
//		if (req.getCandidateId() < this.nodeId)
//			elb.setCandidateId(this.nodeId);
//
//		if (req.getHops() == -1)
//			elb.setHops(-1);
//		else
//			elb.setHops(req.getHops() - 1);
//
//		if (elb.getHops() == 0) {
//			// reverse travel of the message to ensure it gets back to
//			// the originator
//			elb.setHops(mgmt.getHeader().getPathCount());
//
//			// no clear winner, send back the candidate with the highest
//			// known ID. So, if a candidate sees itself, it will
//			// declare itself to be the winner (see above).
//		} else {
//			// forwarding the message on so, keep the history where the
//			// message has been
//			mhb.addAllPath(mgmt.getHeader().getPathList());
//		}
//	}

	// add myself (may allow duplicate entries, if cycling is allowed)
	VectorClock.Builder rpb = VectorClock.newBuilder();
	rpb.setNodeId(this.nodeId);
	rpb.setTime(System.currentTimeMillis());
	rpb.setVersion(req.getElectId());
	mhb.addPath(rpb);

	Management.Builder mb = Management.newBuilder();
	mb.setHeader(mhb.build());
	mb.setElection(elb.build());

	return mb.build();
}

private Management declareWinner(Management mgmt){
		
		LeaderElection req = mgmt.getElection();
		
		LeaderElection.Builder elb = LeaderElection.newBuilder();
		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); // TODO add security

		// reversing path. If I'm the farthest a message can travel, reverse the
		// sending
		if (elb.getHops() == 0)
			mhb.clearPath();
		else
			mhb.addAllPath(mgmt.getHeader().getPathList());

		mhb.setOriginator(mgmt.getHeader().getOriginator());

		elb.setElectId(req.getElectId());
		elb.setAction(ElectAction.DECLAREWINNER);

		elb.setDesc(req.getDesc());
		elb.setLastLogIndex(req.getLastLogIndex());
		elb.setExpires(req.getExpires());
		elb.setCandidateId(req.getCandidateId());

		if (req.getHops() == -1)
			elb.setHops(-1);
		else
			elb.setHops(req.getHops() - 1);

		if (elb.getHops() == 0) {
			// reverse travel of the message to ensure it gets back to
			// the originator
			elb.setHops(mgmt.getHeader().getPathCount());

			// no clear winner, send back the candidate with the highest
			// known ID. So, if a candidate sees itself, it will
			// declare itself to be the winner (see above).
		} else {
			// forwarding the message on so, keep the history where the
			// message has been
			mhb.addAllPath(mgmt.getHeader().getPathList());
		}
		
		// add myself (may allow duplicate entries, if cycling is allowed)
		VectorClock.Builder rpb = VectorClock.newBuilder();
		rpb.setNodeId(this.nodeId);
		rpb.setTime(System.currentTimeMillis());
		rpb.setVersion(req.getTermId());
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		return mb.build();
	}

	@Override
	public Integer getElectionId() {
		if (current == null)
			return null;
		return current.electionID;
	}

	/**
	 * The ID of the election received by the node.
	 * 
	 * This could be different than the ID held by the election instance (this).
	 * If so, what do we do?
	 * 
	 * @param id
	 */
	private void setElectionId(int id) {
		if (current != null) {
			if (current.electionID != id) {
				// need to resolve this!
			}
		}
	}

	
	/**
	 * whoami
	 * 
	 * @return
	 */
	public Integer getNodeId() {
		return nodeId;
	}

	@Override
	public void setNodeId(int nodeId) {
		this.nodeId = nodeId;
	}

	@Override
	public synchronized void clear() {
		current = null;
	}

	@Override
	public boolean isElectionInprogress() {
		return current != null;
	}

	private void notify(boolean success, Integer leader) {
		if (listener != null)
			listener.concludeWith(success, leader);
	}

	public boolean callUpdateCurrent(LeaderElection req) {
		  return updateCurrent(req);
	}
	
	private boolean updateCurrent(LeaderElection req) {
		boolean isNew = false;

		if (current == null) {
			current = new ElectionState();
			isNew = true;
		}

		current.electionID = req.getElectId();
		current.candidate = req.getCandidateId();
		current.desc = req.getDesc();
		current.maxDuration = req.getExpires();
		current.startedOn = System.currentTimeMillis();
		current.state = req.getAction();
		current.id = -1; // TODO me or sender?
		current.active = true;

		return isNew;
	}

	@Override
	public Integer createElectionID() {
		return ElectionIDGenerator.nextID();
	}

	/**
	 * cast a vote based on what I know (my ID) and where the message has
	 * traveled.
	 * 
	 * This is not a pretty piece of code, nor is the problem as we cannot
	 * ensure consistent behavior.
	 * 
	 * @param mgmt
	 * @param isNew
	 * @return
	 */
	private synchronized Management castVote(Management mgmt, boolean isNew) {
		if (!mgmt.hasElection())
			return null;

		if (current == null || !current.isActive()) {
			return null;
		}

		LeaderElection req = mgmt.getElection();
		if (req.getExpires() <= System.currentTimeMillis()) {
			logger.info("Node " + this.nodeId + " says election expired - not voting");
			return null;
		}

//		logger.info("casting vote in election " + req.getElectId() + " by Node: "+this.nodeId +" for Node: "+ req.getCandidateId());

		// okay, the message is new (to me) so I want to determine if I should
		// nominate myself
		
		LeaderElection.Builder elb = LeaderElection.newBuilder();
		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setTime(System.currentTimeMillis());
		mhb.setSecurityCode(-999); // TODO add security
		mhb.setOriginator(mgmt.getHeader().getOriginator());

		elb.setElectId(req.getElectId());
		
		logger.info("Listener's Term ID: "+ listener.getElectId());
		logger.info("Current Term ID: "+ req.getElectId());
		
		if(listener.getElectId() <= req.getElectId() && listener.getLastLogIndex() <= req.getLastLogIndex() && ElectionManager.canVote)
		{
			logger.info("Nominated");
			ElectionManager.canVote = false;
			listener.setElectId(req.getElectId());
			elb.setAction(ElectAction.NOMINATE);
			ElectionIDGenerator.setMasterID(req.getElectId());
		}
		else
		{
			logger.info("Abstained");
			elb.setAction(ElectAction.ABSTAIN);
		}

		elb.setLastLogIndex(req.getLastLogIndex());
		//elb.setAction(ElectAction.NOMINATE);
		elb.setDesc(req.getDesc());
		elb.setExpires(req.getExpires());
		elb.setCandidateId(req.getCandidateId());

//		// my vote
//		if (req.getCandidateId() == this.nodeId) {
//			// if I am not in the list and the candidate is myself, I can
//			// declare myself to be the leader.
//			//
//			// this is non-deterministic as it assumes the message has
//			// reached all nodes in the network (because we know the
//			// diameter or the number of nodes).
//			//
//			// can end up with a partitioned graph of leaders if hops <
//			// diameter!
//
//			// this notify goes out to on-node listeners and will arrive before
//			// the other nodes receive notice.
//			notify(true, this.nodeId);
//
//			elb.setAction(ElectAction.DECLAREWINNER);
//			elb.setHops(mgmt.getHeader().getPathCount());
//			logger.info("Node " + this.nodeId + " is declaring itself the leader");
//		} else {
//			if (req.getCandidateId() < this.nodeId)
//				elb.setCandidateId(this.nodeId);
//
//			if (req.getHops() == -1)
//				elb.setHops(-1);
//			else
//				elb.setHops(req.getHops() - 1);
//
//			if (elb.getHops() == 0) {
//				// reverse travel of the message to ensure it gets back to
//				// the originator
//				elb.setHops(mgmt.getHeader().getPathCount());
//
//				// no clear winner, send back the candidate with the highest
//				// known ID. So, if a candidate sees itself, it will
//				// declare itself to be the winner (see above).
//			} else {
//				// forwarding the message on so, keep the history where the
//				// message has been
//				mhb.addAllPath(mgmt.getHeader().getPathList());
//			}
//		}

		// add myself (may allow duplicate entries, if cycling is allowed)
		VectorClock.Builder rpb = VectorClock.newBuilder();
		rpb.setNodeId(this.nodeId);
		rpb.setTime(System.currentTimeMillis());
		rpb.setVersion(req.getElectId());
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		return mb.build();
	}

	@Override
	public Integer getWinner() {
		if (current == null)
			return null;
		else if (current.state.getNumber() == ElectAction.DECLAREELECTION_VALUE)
			return current.candidate;
		else
			return null;
	}
	
	public synchronized void storeCandidateInfo(int term)
	{

	      //checkArgument(currentTerm < newCurrentTerm, "currentTerm:%s newCurrentTerm:%s", currentTerm, newCurrentTerm);
	      //checkState(commands.isEmpty(), "commands:%s", commands);
	      //checkState(role == Role.FOLLOWER, "invalid transition from %s -> %s", role, Role.CANDIDATE);

	      //logRoleChange(newCurrentTerm, role, Role.CANDIDATE);

	      //stopHeartbeatTimeout();

	      //store.setCurrentTerm(newCurrentTerm);

//	      role = Role.CANDIDATE;
//
//	      setLeader(null);
//
//	      nextToApplyLogIndex = -1;
//	      serverData.clear();
//	      votedServers.clear();
//	      votedServers.put(self, true);
	      /*
	      try {
			store.setVotedFor(term, self);
		} catch (StorageException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/
	}
}
