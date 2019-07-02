/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import sun.java2d.pipe.AlphaColorPipe;

/**
 *combine Spray and wait and Prophet to improve
 *利用Prophet投递预测函数来对Spray and wait算法的改进
 *首先将消息副本根据Prophet算法中的投递概率进行相应的分配，
 *然后每个节点记录与目的节点接触的平均时间间隔和最后相遇的时间间隔，由此做出路由决策
 */
public class ProposeRouter extends ActiveRouter {

	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;	//初始常量
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
	/** Propose router's setting namespace ({@value})*/ 
	public static final String PROPOSE_NS = "ProposeRouter";
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S = "secondsInTimeUint";
	
	/**
	 * Transitivity scaling constant (beta) -setting id ({@value}).
	 * Default value for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";
	
	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;
	
	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/*********************2019-03-25 08:32修改*******************/
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = PROPOSE_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;
	
	/******************2019-03-26 19:10 修改**********************/
	public static double lastUpdateTime = 0;
	public static double t_ab = 0; 	// 节点a与节点b从最后相遇到当前时刻的时间间隔
	public static double m_abNew = 0; 	//节点a与节点b每次相遇间隔的时间的加权平均值
	public static double m_abOld = 0; 	//节点a与节点b每次相遇间隔的时间的加权平均值
	private double alpha = 0.25;		// 更新t和m的参数
	public static double laNew;		//重新分配给节点a的副本数量
	public static double laOld;
	public static double lbOld;
	public static double lbNew;

	/***********************************************************/
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public ProposeRouter(Settings s) {
		super(s);
		// TODO Auto-generated constructor stub
		Settings proposeSettings = new Settings(PROPOSE_NS);
		secondsInTimeUnit = proposeSettings.getInt(SECONDS_IN_UNIT_S);
		if (proposeSettings.contains(BETA_S)) {
			beta = proposeSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		initialNrofCopies = proposeSettings.getInt(NROF_COPIES);//初始化副本数量
		isBinary = proposeSettings.getBoolean( BINARY_MODE);
		
		initPreds();
		
	}
	
	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected ProposeRouter(ProposeRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
	
		initPreds();	
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}
	
	/**********************2019-03-25 09:09 修改***************/
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		/* create a list of SAWMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			//this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}
	
	/**
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}
		
		return list;
	}
	
	/**
	 * Called just before a transfer is finalized (by 
	 * {@link ActiveRouter#update()}).
	 * Reduces the number of copies we have left for a message. 
	 * In binary Spray and Wait, sending host is left with floor(n/2) copies,
	 * but in standard mode, nrof copies left is reduced by one. 
	 */
	@Override
	protected void transferDone(Connection con) {
		//更新发送端节点副本数量
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		
		/* reduce the amount of copies left */
		nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) { 
			nrofCopies /= 2;
		}
		else {
			//利用Prophet算法投递预测函数重新计算副本数量
			

		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		//更新接收端节点副本数量
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a SnW message: " + msg;
		
		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int)Math.ceil(nrofCopies/2.0);
		}
		else {
			//利用Prophet算法投递预测函数重新计算副本数量
		}
		
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}



	/*********************************************************/
	
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
	}
	
	/*******************************************************/
	/**
	 * 当两个节点相遇时，比较两者到目的节点的投递预测值，
	 * 并根据自身占总体的比重获取分配新的消息副本数量
	 * m_d表示目的地为节点d的消息，P(a,d)是节点a到节点d的传输预测值
	 * L_a_old(m_d)表示当前节点a携带的消息副本数量
	 * L_a_new(m_d)表示重新分配给节点a的消息副本数量
	 * <CODE> L_a_new(m_d) = P(a,d) / (P(a,d)+P(b,d)) * (L_a_old(m_d)+L_b_old(m_d)) 
	 * 转化 L_a_new(m_d) = P(a,b) / (P(a,b)+P(a,c)) * (L_a_old(m_d)+L_b_old(m_d)) 
	 * L_b_new(m_d)=L_a_old(m_d)+L_b_old(m_d)-L_a_new(m_d)
	 * </CODE>
	 */
	private Integer updateMessageCopy(DTNHost host, Integer nrofCopies) {
		double oldValue = getPredFor(host);		//P(a,b)_old
		double newValue = oldValue + (1 - oldValue) * P_INIT;	//P(a,b)
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProposeRouter : "PRoPOSE only works " +
				" with other routers of same type";
		
		double pForHost = newValue; 	//		P(a,b)_new
		Map<DTNHost,Double> othersPreds = 
				((ProposeRouter)otherRouter).getDeliveryPreds();
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue;
			}
			
			double pOld = getPredFor(e.getKey());	//P(a,c)_old
			//e.getValue() -> P(b,c)的概率值
			double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta; //P(a,c)_new
			laNew = (newValue / (newValue + pNew)) * (laOld + lbOld);
			lbNew = laOld + lbOld - laNew;
		}
		updateMessageAfterInterver(host);

		return nrofCopies;
		
	}

	/**
	 *利用时间信息动态调整消息副本数。
	 *每当两节点相遇的时间间隔更新完成后，再动态调整节点所携带的消息副本的数量
	 *每个节点需要记录两个时间间隔r(a,b)和m(a,b)
	 *r(a,b)表示节点a与节点b从最后相遇到当前时刻的时间间隔
	 *m(a,b)表示节点a与节点b每次相遇间隔的时间的加权平均值
	 *<CODE> m(a,b)_new = alpha * m(a,b)_old + (1 - alpha) * r(a,b)
	 * L_a_new(m_d) = L_a_old(m_d) * r(a,b) / m(a,b)
	 *</CODE>
	 */
	private void updateMessageAfterInterver(DTNHost host) {
		t_ab = SimClock.getTime() - lastUpdateTime;
		lastUpdateTime = SimClock.getTime();
		m_abNew = alpha * m_abOld + (1 - alpha) * t_ab;
		laNew = laOld * t_ab / m_abNew;
	}
	
	/*******************************************************/
	

	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		//更新节点的投递预测值
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}
	
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		// 投递预测值的传递性
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProposeRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((ProposeRouter)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue() * beta;	//P(a,c)
			preds.put(e.getKey(), pNew);
		}
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		// 投递预测值的衰减
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
				secondsInTimeUnit;
			
			if (timeDiff == 0) {
				return;
			}
			
			double mult = Math.pow(GAMMA, timeDiff);
			for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
				e.setValue(e.getValue()*mult);
			}
			
			this.lastAgeUpdate = SimClock.getTime();
	}

	/**************************2019-03-25 09:06修改*****************/
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}

	/**************************************************************/
	@Override
	public ProposeRouter replicate() {
		// TODO Auto-generated method stub
		return new ProposeRouter(this);
	}
	
}
