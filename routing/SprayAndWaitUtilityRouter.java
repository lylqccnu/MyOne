/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import util.Tuple;

/**
 * Implementation of Spray and wait router as depicted in <I>Spray and Wait: An
 * Efficient Routing Scheme for Intermittently Connected Mobile Networks</I> by
 * Thrasyvoulos Spyropoulus et al.
 *
 */
public class SprayAndWaitUtilityRouter extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value}) */
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value}) */
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value}) */
	public static final String SPRAYANDWAITUTILITY_NS = "SprayAndWaitUtilityRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAITUTILITY_NS + "." + "copies";

	protected int initialNrofCopies;
	protected boolean isBinary;

	// 节点收到的总消息数量
	private int count;

	// 节点转发的消息数
	private int diliveryCount;

	// 节点剩余缓存
	private int freeBuff;

	// 节点总缓存
	private int buffSize;

	// 节点剩余缓存的权重
	private double pro_buff = 0.75;

	// 节点交付概率的权重
	private double pro_delivery = 0.25;

	// 节点的效用值
	private Map<DTNHost, Double> utilitys;

	public SprayAndWaitUtilityRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAITUTILITY_NS);

		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean(BINARY_MODE);

		count = 0;
		diliveryCount = 0;
		freeBuff = 0;
		buffSize = 0;

		initUtility();
	}

	/**
	 * Copy constructor.
	 * 
	 * @param r
	 *            The router prototype where setting values are copied from
	 */
	protected SprayAndWaitUtilityRouter(SprayAndWaitUtilityRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;

		this.count = r.count;
		this.diliveryCount = r.diliveryCount;
		this.freeBuff = r.freeBuff;
		this.buffSize = r.buffSize;

		initUtility();
	}

	/** 初始化节点效用值 */
	private void initUtility() {
		// TODO Auto-generated method stub
		this.utilitys = new HashMap<DTNHost, Double>();
	}

	@Override
	public int receiveMessage(Message m, DTNHost from) {
		// 节点收到的消息数量
		count += m.getSize();
		return super.receiveMessage(m, from);
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);

		assert nrofCopies != null : "Not a SnW message: " + msg;

		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int) Math.ceil(nrofCopies / 2.0);// 向上取整
			//节点转发的消息数量
			diliveryCount += nrofCopies;
		} else {
			/* in standard S'n'W the receiving node gets only single copy */
			nrofCopies = 1;
		}

		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}

	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}

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

		trySAWUtilityMessage();
	}

	private Tuple<Message, Connection> trySAWUtilityMessage() {
		// TODO Auto-generated method stub

		@SuppressWarnings(value = "unchecked")
		/**
		 * 3）得到节点i还有副本需要喷洒的消息列表，将其按照随机模式，并赋值给新的消息列表 默认按照随机模式
		 */
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());

		List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

		// 返回集合中此路由的消息集合
		Collection<Message> msgCollection = getMessageCollection();

		/**
		 * 4) 节点j是节点i某时刻所有连接中的一个， 如果节点i还有消息副本需要喷洒即copiestLeft.size() > 0 则进行下面的操作
		 **/
		if (copiesLeft.size() > 0) {
			/**
			 * 先计算节点j的效用值utilityj，与节点i的效用值utilityi进行比较 如果 utilityj >= utilityi
			 * 则将节点j加入“消息-连接”对应表L中 所以L中存有的所有节点的效用值都大于或等于节点i的效用值， 如果此刻节点i还有连接节点，则重复3）、4）
			 * 
			 */
			for (Connection con : getConnections()) {
				DTNHost other = con.getOtherNode(getHost());
				SprayAndWaitUtilityRouter otherRouter = (SprayAndWaitUtilityRouter) other.getRouter();

				if (otherRouter.isTransferring()) {
					continue;
				}

				for (Message m : msgCollection) {
					if (otherRouter.hasMessage(m.getId())) {
						continue;
					}
					if (otherRouter.getUtilityFor(m.getTo()) >= getUtilityFor(m.getTo())) {
						messages.add(new Tuple<Message, Connection>(m, con));
					}
				}
			}
		}
		if (messages.size() == 0) {
			return null;
		}

		// sort the message-connection tuples
		Collections.sort(messages, new TupleComparator());

		return tryMessagesForConnected(messages); // try to send messages
	}

	private class TupleComparator implements Comparator<Tuple<Message, Connection>> {
		public int compare(Tuple<Message, Connection> tuple1, 
				Tuple<Message, Connection> tuple2) {
			// utility of tuple1's message with tuple1's connection
			double u1 = ((SprayAndWaitUtilityRouter) tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getUtilityFor(
							tuple1.getKey().getTo());
			
			double u2 = ((SprayAndWaitUtilityRouter) tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getUtilityFor(
							tuple2.getKey().getTo());

			// bigger probability should come first
			if (u2 - u1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			} else if (u2 - u1 < 0) {
				return -1;
			} else {
				return 1;
			}
		}
	}

	/** 返回节点的效用值 */
	public double getUtilityFor(DTNHost host) {
		updateUtility(host);
		if (utilitys.containsKey(host)) {
			return utilitys.get(host);
		} else {
			return 0;
		}
	}

	/** 更新节点的效用值 */
	private void updateUtility(DTNHost host) {
		// 1.获取节点剩余缓存
		// 2.获取节点总缓存
		// 3.获取节点转发的消息数量
		// 4.获取节点收到的总消息数
		// 5.计算节点效用值
		// 6.保存效用值

		freeBuff = getFreeBufferSize();
		buffSize = getBufferSize();
		double bufferSizeRadio = freeBuff / buffSize;
		double diliveryRadio = diliveryCount / count;
		double utility = pro_buff * bufferSizeRadio + pro_delivery * diliveryRadio;
		utilitys.put(host, utility);
	}

	/**
	 * Creates and returns a list of messages this router is currently carrying and
	 * still has copies left to distribute (nrof copies > 1).
	 * 
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + "nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}

		return list;
	}

	/**
	 * Called just before a transfer is finalized (by
	 * {@link ActiveRouter#update()}). Reduces the number of copies we have left for
	 * a message. In binary Spray and Wait, sending host is left with floor(n/2)
	 * copies, but in standard mode, nrof copies left is reduced by one.
	 */
	@Override
	protected void transferDone(Connection con) {

		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}

		/* reduce the amount of copies left */
		nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) {
			nrofCopies /= 2;
		} else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}

	@Override
	public SprayAndWaitUtilityRouter replicate() {
		return new SprayAndWaitUtilityRouter(this);
	}
}
