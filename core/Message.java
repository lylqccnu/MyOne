/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A message that is created at a node or passed between nodes.
 */
public class Message implements Comparable<Message> {
	/** Time-to-live (TTL) as seconds -setting id ({@value}). Boolean valued.
	 * If set to true, the TTL is interpreted as seconds instead of minutes. 
	 * Default=false. */
	public static final String TTL_SECONDS_S = "Scenario.ttlSeconds";
	private static boolean ttlAsSeconds = false;
	
	/** Value for infinite TTL of message */
	public static final int INFINITE_TTL = -1;
	private DTNHost from;
	private DTNHost to;
	/** Identifier of the message */
	private String id;
	/** Size of the message (bytes) */
	private int size;
	/** List of nodes this message has passed */
	private List<DTNHost> path; 
	/** Next unique identifier to be given */
	private static int nextUniqueId;
	/** Unique ID of this message */
	private int uniqueId;
	/** The time this message was received */
	private double timeReceived;
	/** The time when this message was created */
	private double timeCreated;
	/** Initial TTL of the message */
	private int initTtl;
	
	/** if a response to this message is required, this is the size of the 
	 * response message (or 0 if no response is requested) */
	private int responseSize;
	/** if this message is a response message, this is set to the request msg*/
	private Message requestMsg;
	
	/** Container for generic message properties. Note that all values
	 * stored in the properties should be immutable because only a shallow
	 * copy of the properties is made when replicating messages */
	private Map<String, Object> properties;
	
	/** Application ID of the application that created the message */
	private String	appID;
	
	static {
		reset();
		DTNSim.registerForReset(Message.class.getCanonicalName());
	}
	
	/**
	 * Creates a new Message.
	 * @param from Who the message is (originally) from
	 * @param to Who the message is (originally) to
	 * @param id Message identifier (must be unique for message but
	 * 	will be the same for all replicates of the message)
	 * @param size Size of the message (in bytes)
	 */
	public Message(DTNHost from, DTNHost to, String id, int size) {
		this.from = from;
		this.to = to;
		this.id = id;
		this.size = size;
		this.path = new ArrayList<DTNHost>();
		this.uniqueId = nextUniqueId;
		
		this.timeCreated = SimClock.getTime();
		this.timeReceived = this.timeCreated;
		this.initTtl = INFINITE_TTL;
		this.responseSize = 0;
		this.requestMsg = null;
		this.properties = null;
		this.appID = null;
		
		Message.nextUniqueId++;
		addNodeOnPath(from);
	}
	
	/**消息源
	 * Returns the node this message is originally from
	 * @return the node this message is originally from
	 */
	public DTNHost getFrom() {
		return this.from;
	}

	/**消息目的地
	 * Returns the node this message is originally to
	 * @return the node this message is originally to
	 */
	public DTNHost getTo() {
		return this.to;
	}

	/**消息ID
	 * Returns the ID of the message
	 * @return The message id
	 */
	public String getId() {
		return this.id;
	}
	
	/**返回每个消息实例唯一的ID(对于复制也不同)
	 * Returns an ID that is unique per message instance 
	 * (different for replicates too)
	 * @return The unique id
	 */
	public int getUniqueId() {
		return this.uniqueId;
	}
	
	/**返回消息大小(多少bytes)
	 * Returns the size of the message (in bytes)
	 * @return the size of the message
	 */
	public int getSize() {
		return this.size;
	}

	/**在此消息已传递的节点列表中添加新节点
	 * Adds a new node on the list of nodes this message has passed
	 * @param node The node to add
	 */
	public void addNodeOnPath(DTNHost node) {
		this.path.add(node);
	}
	
	/**返回此消息至今已传递的节点列表
	 * Returns a list of nodes this message has passed so far
	 * @return The list as vector
	 */
	public List<DTNHost> getHops() {
		return this.path;
	}
	
	/**返回此消息已传递的跳数
	 * Returns the amount of hops this message has passed
	 * @return the amount of hops this message has passed
	 */
	public int getHopCount() {
		return this.path.size() -1;
	}
	
	/** 返回节点生存周期TTL(分钟或秒，根据配置文件来确定)
	 * Returns the time to live (in minutes or seconds, depending on the setting
	 * {@link #TTL_SECONDS_S}) of the message or Integer.MAX_VALUE 
	 * if the TTL is infinite. Returned value can be negative if the TTL has
	 * passed already.
	 * @return The TTL
	 */
	public int getTtl() {
		if (this.initTtl == INFINITE_TTL) {
			return Integer.MAX_VALUE;
		}
		else {
			if (ttlAsSeconds) {
				return (int)(this.initTtl -
						(SimClock.getTime()-this.timeCreated) );				
			} else {
				return (int)( ((this.initTtl * 60) -
						(SimClock.getTime()-this.timeCreated)) /60.0 );
			}
		}
	}
	
	/**给消息设置TTL
	 * Sets the initial TTL (time-to-live) for this message. The initial
	 * TTL is the TTL when the original message was created. The current TTL
	 * is calculated based on the time of 
	 * @param ttl The time-to-live to set
	 */
	public void setTtl(int ttl) {
		this.initTtl = ttl;
	}
	
	/**设置接收此消息的时间
	 * Sets the time when this message was received.
	 * @param time The time to set
	 */
	public void setReceiveTime(double time) {
		this.timeReceived = time;
	}
	
	/**返回接收此消息的时间
	 * Returns the time when this message was received
	 * @return The time
	 */
	public double getReceiveTime() {
		return this.timeReceived;
	}
	
	/**返回创建此消息的时间
	 * Returns the time when this message was created
	 * @return the time when this message was created
	 */
	public double getCreationTime() {
		return this.timeCreated;
	}
	
	/**如果此消息是对请求的响应，则设置请求消息(可能在ACK的时候用到)
	 * If this message is a response to a request, sets the request message
	 * @param request The request message
	 */
	public void setRequest(Message request) {
		this.requestMsg = request;
	}
	
	/**返回此消息为响应消息，如果不是响应消息则返回null
	 * Returns the message this message is response to or null if this is not
	 * a response message
	 * @return the message this message is response to
	 */
	public Message getRequest() {
		return this.requestMsg;
	}
	
	/**如果此消息是响应消息则返回true否则返回false
	 * Returns true if this message is a response message
	 * @return true if this message is a response message
	 */
	public boolean isResponse() {
		return this.requestMsg != null;
	}
	
	/**设置请求的响应消息的大小，如果size==0，则不请求响应(默认)
	 * Sets the requested response message's size. If size == 0, no response
	 * is requested (default)
	 * @param size Size of the response message
	 */
	public void setResponseSize(int size) {
		this.responseSize = size;
	}
	
	/**返回请求的响应消息的大小，如果没有请求响应则返回0
	 * Returns the size of the requested response message or 0 if no response
	 * is requested.
	 * @return the size of the requested response message
	 */
	public int getResponseSize() {
		return responseSize;
	}
	
	/**
	 * Returns a string representation of the message
	 * @return a string representation of the message
	 */
	public String toString () {
		return id;
	}

	/**从其他消息深度拷贝消息数据，如果这个类引入了新字段，它们很可能也应该复制到这里
	 * (除非在构造函数已经完成)
	 * Deep copies message data from other message. If new fields are
	 * introduced to this class, most likely they should be copied here too
	 * (unless done in constructor).
	 * @param m The message where the data is copied
	 */
	protected void copyFrom(Message m) {
		this.path = new ArrayList<DTNHost>(m.path);
		this.timeCreated = m.timeCreated;
		this.responseSize = m.responseSize;
		this.requestMsg  = m.requestMsg;
		this.initTtl = m.initTtl;
		this.appID = m.appID;
		
		if (m.properties != null) {
			Set<String> keys = m.properties.keySet();
			for (String key : keys) {
				updateProperty(key, m.getProperty(key));
			}
		}
	}
	
	/**为此消息添加泛型属性,key可以是任意的字符串，但必须没有其他类意外的使用相同的值
	 * Adds a generic property for this message. The key can be any string but 
	 * it should be such that no other class accidently uses the same value.
	 * The value can be any object but it's good idea to store only immutable
	 * objects because when message is replicated, only a shallow copy of the
	 * properties is made.  
	 * @param key The key which is used to lookup the value
	 * @param value The value to store
	 * @throws SimError if the message already has a value for the given key
	 */
	public void addProperty(String key, Object value) throws SimError {
		if (this.properties != null && this.properties.containsKey(key)) {
			/* check to prevent accidental name space collisions */
			throw new SimError("Message " + this + " already contains value " + 
					"for a key " + key);
		}
		
		this.updateProperty(key, value);
	}
	
	/**返回使用指定的Key存储到此消息的对象，如果没有找到这样的对象，则返回null
	 * Returns an object that was stored to this message using the given
	 * key. If such object is not found, null is returned.
	 * @param key The key used to lookup the object
	 * @return The stored object or null if it isn't found
	 */
	public Object getProperty(String key) {
		if (this.properties == null) {
			return null;
		}
		return this.properties.get(key);
	}
	
	/**更新现有实行的值,对于第一次存储值，应该使用{@link #addProperty(String, Object)}
	 * 来检查名称空间冲突
	 * Updates a value for an existing property. For storing the value first 
	 * time, {@link #addProperty(String, Object)} should be used which
	 * checks for name space clashes.
	 * @param key The key which is used to lookup the value
	 * @param value The new value to store
	 */
	public void updateProperty(String key, Object value) throws SimError {
		if (this.properties == null) {
			/* lazy creation to prevent performance overhead for classes
			   that don't use the property feature  */
			this.properties = new HashMap<String, Object>();
		}		

		this.properties.put(key, value);
	}
	
	/**返回此消息的副本(除了唯一ID外，其他内容相同)
	 * Returns a replicate of this message (identical except for the unique id)
	 * @return A replicate of the message
	 */
	public Message replicate() {
		Message m = new Message(from, to, id, size);
		m.copyFrom(this);
		return m;
	}
	
	/**比较两个消息的ID(通过字母顺序)
	 * Compares two messages by their ID (alphabetically).
	 * @see String#compareTo(String)
	 */
	public int compareTo(Message m) {
		return toString().compareTo(m.toString());
	}
	
	/**将所有静态字段设置为默认值
	 * Resets all static fields to default values
	 */
	public static void reset() {
		nextUniqueId = 0;
		Settings s = new Settings();
		ttlAsSeconds = s.getBoolean(TTL_SECONDS_S, false);
	}

	/**
	 * @return the appID
	 */
	public String getAppID() {
		return appID;
	}

	/**
	 * @param appID the appID to set
	 */
	public void setAppID(String appID) {
		this.appID = appID;
	}
	
}
