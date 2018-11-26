/**
 * 
 */
package com.apama;

import static com.softwareag.connectivity.StatusReporter.STATUS_FAILED;
import static com.softwareag.connectivity.StatusReporter.STATUS_ONLINE;
import static com.softwareag.connectivity.StatusReporter.STATUS_STARTING;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;

import com.apama.transport.consumerTransport;
import com.apama.transport.producerTransport;
import com.softwareag.connectivity.AbstractSimpleTransport;
import com.softwareag.connectivity.AbstractTransport;
import com.softwareag.connectivity.Direction;
import com.softwareag.connectivity.StatusReporter;
import com.softwareag.connectivity.StatusReporter.StatusItem;
import com.softwareag.connectivity.chainmanagers.AbstractChainManager;
import com.softwareag.connectivity.chainmanagers.Chain;
import com.softwareag.connectivity.chainmanagers.ChainManagerConstructorParameters;
import com.softwareag.connectivity.chainmanagers.ChannelLifecycleListener;
import com.softwareag.connectivity.chainmanagers.ManagedTransportConstructorParameters;
import com.softwareag.connectivity.util.MapExtractor;

/**
 * @author Prabal Nandi
 *
 */
public class pulsarChainManager extends AbstractChainManager implements ChannelLifecycleListener {

	/** Identifies the version of the API this plug-in was built against. */
	public static final String CONNECTIVITY_API_VERSION = com.softwareag.connectivity.ConnectivityPlugin.CONNECTIVITY_API_VERSION;
	// Configuration constants
	public static final String CHANNEL_PREFIX_KEY = "channelPrefix";
	public static final String DEFAULT_CHANNEL_PREFIX = "pulsar:";
	public static final String CHANNEL_PREFIX_FROM = "FromPulsar";
	public static final String CHANNEL_PREFIX_TO = "ToPulsar";
	public static final String PRODUCER_CONFIG_KEY = "producerConfig";
	public static final String CONSUMER_CONFIG_KEY = "consumerConfig";
	private static final String ENABLE_AUTO_ACKNOWLEDGEMENT = "enable.auto.acknowledgement";
	private static final String CONSUMER_START_TIMEOUT = "consumer.start.timeout";
	private static final String ACK_REQUEST_FREQUENCY = "ackrequest.frequency";

	private String channelPrefix;
	private final boolean autoAcknowledgement;
	private final int consumerStartTimeout;
	// Member variables to facilitate Sending acknowledgement explicitly by EPL app
	private final float ackRequestFrequency;

	private final Map<String, Object> connectionPropertyMap = new HashMap<>();
	private Map<String, Object> producerPropertyMap = new HashMap<>();
	private Map<String, Object> consumerPropertyMap = new HashMap<>();
	private final Lock lock = new ReentrantLock();
	private Map<String, Chain> exisitingChains = new HashMap<String, Chain>();
	private PulsarClient pulsarClient;
	private final Set<String> unrelatedPropertySet = new HashSet<>(4);

	// Status monitoring and KPIs. See naming conventions described in
	// documentation.
	private final StatusItem consumedStatus;
	private final StatusItem producedStatus;
	private final StatusItem statusItem;

	public pulsarChainManager(Logger logger, ChainManagerConstructorParameters params)
			throws IllegalArgumentException, Exception {
		super(logger, params);

		MapExtractor mapExtractor = new MapExtractor(params.getManagerConfig(), "Chain Manager Configuration");
		this.channelPrefix = params.getManagerConfig().getOrDefault(CHANNEL_PREFIX_KEY, DEFAULT_CHANNEL_PREFIX)
				.toString();
		this.autoAcknowledgement = mapExtractor.get(ENABLE_AUTO_ACKNOWLEDGEMENT, Boolean.class, true);
		this.consumerStartTimeout = mapExtractor.get(CONSUMER_START_TIMEOUT, Integer.class, 60);
		this.ackRequestFrequency = mapExtractor.get(ACK_REQUEST_FREQUENCY, Float.class, 0.20f);

		this.unrelatedPropertySet.add(CHANNEL_PREFIX_KEY);
		this.unrelatedPropertySet.add(ENABLE_AUTO_ACKNOWLEDGEMENT);
		this.unrelatedPropertySet.add(CONSUMER_START_TIMEOUT);
		this.unrelatedPropertySet.add(ACK_REQUEST_FREQUENCY);
		this.unrelatedPropertySet.add(PRODUCER_CONFIG_KEY);
		this.unrelatedPropertySet.add(CONSUMER_CONFIG_KEY);

		// Initialize - Read configuration from chainManager properties file and create
		// respective configurations
		init(logger, mapExtractor);

		// Setup status monitoring and KPIs
		StatusReporter reporter = params.getStatusReporter();
		statusItem = reporter.createStatusItem(managerName + ".status", STATUS_STARTING);
		consumedStatus = reporter.createStatusItem(managerName + ".consumed", 0);
		producedStatus = reporter.createStatusItem(managerName + ".produced", 0);
		reporter.createStatusItem(managerName + ".KPIs",
				String.join(",", Arrays.asList(consumedStatus.getKey(), producedStatus.getKey())));
	}

	/**
	 * reads all properties passed to connectivity plugin via chain manager
	 * properties file.
	 * 
	 * @param logger
	 * @param params
	 * @throws IllegalArgumentException
	 */
	@SuppressWarnings("unchecked")
	private void init(Logger logger, MapExtractor mapExtractor) throws IllegalArgumentException {
		try {
			this.producerPropertyMap = (Map<String, Object>) mapExtractor.getMap(PRODUCER_CONFIG_KEY, true)
					.getUnderlyingMap();
			this.consumerPropertyMap = (Map<String, Object>) mapExtractor.getMap(CONSUMER_CONFIG_KEY, true)
					.getUnderlyingMap();
			mapExtractor.getUnderlyingMap().entrySet().stream().filter(entry -> {
				return !this.unrelatedPropertySet.contains(entry.getKey());
			}).forEach(entry -> this.connectionPropertyMap.put(entry.getKey().toString(), entry.getValue()));

			// Check mandatory Client URl Field
			if (!this.connectionPropertyMap.containsKey("serviceUrl")
					|| this.connectionPropertyMap.get("serviceUrl") == null) {
				throw new Exception("Service URL is missing in the configuration file");
			}
		} catch (Exception exception) {
			throw new IllegalArgumentException(exception.getMessage());
		}
	}

	/**
	 * Overriding
	 * {@link AbstractChainManager#createTransport(Logger, ManagedTransportConstructorParameters)}
	 * method.
	 * 
	 * This implementation creates two different instances of
	 * {@link AbstractSimpleTransport} to handle HOST to External Transport and
	 * External Transport to HOST messages.
	 */
	@Override
	public AbstractTransport createTransport(Logger logger, ManagedTransportConstructorParameters params)
			throws IllegalArgumentException, Exception {

		logger.info("createTransport " + params.getSubscribeChannels().isEmpty());
		return params.getSubscribeChannels().isEmpty() ? new consumerTransport(logger, params, this)
				: new producerTransport(logger, params, this);
	}

	@Override
	public void shutdown() throws Exception {
		synchronized (lock) {
			exisitingChains.values().forEach(chain -> destroyChain(chain));
			exisitingChains = null;
			this.pulsarClient.close();
		}
		this.logger.debug("Shutdown of ChainManager {} complete", managerName);
	}

	@Override
	public void start() throws Exception {
		// Add listener for channel creation/destruction with our prefix
		host.addChannelLifecycleListener(this, channelPrefix);
		try {
			this.pulsarClient = PulsarClient.builder().loadConf(this.getConnectionPropertyMap()).build();
		} catch (PulsarClientException pulsarClient) {
			statusItem.setStatus(STATUS_FAILED);
			throw new Exception(pulsarClient);
		}
		statusItem.setStatus(STATUS_ONLINE);
		this.logger.info("Pulsar Chain Manager Started @ " + new Date(System.currentTimeMillis()));
	}

	@Override
	public void onChannelCreated(String channel, Direction direction) throws Exception {
		try {
			Chain chain = null;
			synchronized (lock) {
				// Check this channel and direction doesn't already exist
				final String prefixedChannel = prefixDirectionToChannel(channel, direction);
				if (null == exisitingChains || null != exisitingChains.get(prefixedChannel)) {
					return;
				}
				// Create our chain
				chain = host.createChain(prefixedChannel, getChainDefinition(), getSubstitutions(),
						getHostChannel(channel, direction), getChannels(channel, direction));
				exisitingChains.put(prefixedChannel, chain);
			}
			chain.start();
		} catch (Exception e) {
			logger.error("Failed to create: channel=\"{}\", direction=\"{}\", with exception: ", channel, direction, e);
		}
	}

	@Override
	public void onChannelDestroyed(String channel, Direction direction) throws Exception {
		Chain chain = null;
		synchronized (lock) {
			if (exisitingChains != null) {
				final String prefixedChannel = prefixDirectionToChannel(channel, direction);
				chain = exisitingChains.get(prefixedChannel);
				exisitingChains.remove(prefixedChannel);
			}
		}
		destroyChain(chain);
	}

	private void destroyChain(Chain chain) {
		if (null != chain) {
			try {
				chain.destroy();
			} catch (Exception e) {
				logger.error("Error during chain-destroy {}: {}", chain.getTransport().chainId, e);
			}
		}
	}

	private String prefixDirectionToChannel(String channel, Direction direction) {
		return (direction == Direction.TOWARDS_HOST ? CHANNEL_PREFIX_FROM : CHANNEL_PREFIX_TO) + "["
				+ channel.substring(channelPrefix.length()) + "]";
	}

	private final Map<String, String> getSubstitutions() {
		return null;
	}

	private static final String getHostChannel(String channel, Direction direction) {
		return (direction == Direction.TOWARDS_HOST ? channel : null);
	}

	private static final List<String> getChannels(String channel, Direction direction) {
		return (direction == Direction.TOWARDS_TRANSPORT ? Collections.singletonList(channel) : null);
	}

	// Getters and Setters
	public Map<String, Object> getConnectionPropertyMap() {
		return connectionPropertyMap;
	}

	public Map<String, Object> getProducerPropertyMap() {
		return producerPropertyMap;
	}

	public Map<String, Object> getConsumerPropertyMap() {
		return consumerPropertyMap;
	}

	public PulsarClient getPulsarClient() {
		return pulsarClient;
	}

	public StatusItem getProducedStatus() {
		return producedStatus;
	}

	public StatusItem getConsumedStatus() {
		return consumedStatus;
	}

	public boolean isAutoAckEnabled() {
		return this.autoAcknowledgement;
	}

	public int getConsumerStartTimeout() {
		return consumerStartTimeout;
	}

	public float getAckRequestFrequency() {
		return ackRequestFrequency;
	}

}
