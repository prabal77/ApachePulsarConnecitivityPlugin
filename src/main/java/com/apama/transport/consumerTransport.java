/**
 * 
 */
package com.apama.transport;

import static com.softwareag.connectivity.StatusReporter.STATUS_ONLINE;
import static com.softwareag.connectivity.StatusReporter.STATUS_STARTING;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;

import com.apama.PulsarChainManager;
import com.apama.util.ExceptionUtil;
import com.softwareag.connectivity.AbstractSimpleTransport;
import com.softwareag.connectivity.Direction;
import com.softwareag.connectivity.Message;
import com.softwareag.connectivity.PluginConstructorParameters.TransportConstructorParameters;
import com.softwareag.connectivity.StatusReporter;
import com.softwareag.connectivity.StatusReporter.StatusItem;

/**
 * @author NANP
 *
 */
public class ConsumerTransport extends AbstractSimpleTransport {

	private final PulsarChainManager chainManager;
	private Consumer<byte[]> consumer;
	private ConsumerBuilder<byte[]> consumerBuilder;
	private CompletableFuture<Consumer<byte[]>> future;

	private final StatusItem managerConsumedStatus;
	private final StatusItem consumedStatus;
	private final StatusItem statusItem;
	private volatile Lock lock = new ReentrantLock();
	private final AtomicInteger messageCount = new AtomicInteger(0);
	private final int maxReceiverQueueSize;
	private final Map<String, org.apache.pulsar.client.api.Message<byte[]>> messageIdMap;

	public ConsumerTransport(Logger logger, TransportConstructorParameters params, PulsarChainManager chainManager)
			throws IllegalArgumentException, Exception {
		super(logger, params);
		// Save the callee chainManager instance
		this.chainManager = chainManager;
		this.maxReceiverQueueSize = Integer.parseInt(
				this.chainManager.getConsumerPropertyMap().getOrDefault("receiverQueueSize", 1000).toString());
		this.messageIdMap = new HashMap<String, org.apache.pulsar.client.api.Message<byte[]>>(
				(int) (this.chainManager.getAckRequestFrequency() * this.maxReceiverQueueSize));

		// Setup status monitoring and KPIs
		final String statusPrefix = String.format("%s.%s.%s", PulsarChainManager.CHANNEL_PREFIX_FROM,
				chainManager.managerName, this.chainManager.getConsumerPropertyMap().getOrDefault("topic", ""));
		statusItem = getStatusReporter().createStatusItem(statusPrefix + ".status", STATUS_STARTING);

		consumedStatus = getStatusReporter().createStatusItem(statusPrefix + ".consumed", 0);
		getStatusReporter().createStatusItem(String.format("%s.KPIs", statusPrefix),
				String.join(",", Arrays.asList(consumedStatus.getKey())));
		managerConsumedStatus = chainManager.getConsumedStatus();
	}

	@Override
	public void start() throws Exception {
		try {
			if (this.lock.tryLock()) {
				// Enable Reliable Messaging if required
				if (!this.chainManager.isAutoAckEnabled()) {
					host.enableReliability(Direction.TOWARDS_HOST);
				}
				this.consumerBuilder = this.chainManager.getPulsarClient().newConsumer()
						.loadConf(this.chainManager.getConsumerPropertyMap())
						.messageListener(createMessageListenerStrategy(this.chainManager));
			}

		} catch (Exception exception) {
			ExceptionUtil.propagateException(exception);
			statusItem.setStatus(StatusReporter.STATUS_FAILED);
			throw exception;
		} finally {
			this.lock.unlock();
		}
		statusItem.setStatus(StatusReporter.STATUS_ONLINE);
	}

	@Override
	public void hostReady() throws Exception {
		// Try to subscribe to Pulsar Channel, default wait time 60 seconds
		this.future = this.consumerBuilder.subscribeAsync();
		try {
			this.future.thenAccept(obj -> {
				this.consumer = obj;
			}).get(this.chainManager.getConsumerStartTimeout(), TimeUnit.SECONDS);
		} catch (CancellationException | TimeoutException | InterruptedException | ExecutionException exception) {
			statusItem.setStatus(StatusReporter.STATUS_FAILED);
			throw new RuntimeException("Pulsar Subscribe command timed out " + exception.getMessage(), exception);
		}
		super.hostReady();
		logger.info("Pulsar Consumer transport Host ready routine successful");
	}

	@Override
	public void deliverMessageTowardsTransport(Message arg0) throws Exception {
		throw new UnsupportedOperationException(
				"It's a consumer transport plugin. Cannot send \"Real\" message back to host.");
	}

	@Override
	public void shutdown() throws Exception {
		try {
			this.lock.lockInterruptibly();
			if (this.consumer != null)
				this.consumer.close();
			this.consumerBuilder = null;
			this.messageIdMap.clear();
			logger.info("Pulsar Consumer shutdown successfully");

		} catch (PulsarClientException pulsarClientException) {
			ExceptionUtil.getMessageFromException(pulsarClientException);
			statusItem.setStatus(StatusReporter.STATUS_FAILED);
			throw new RuntimeException("Unable to shutdown pulsar consumers - "+ pulsarClientException.getMessage(), pulsarClientException);
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 
	 * @param messageId
	 * @return boolean
	 */
	public boolean sendAcknowledgement(org.apache.pulsar.client.api.Message<byte[]> message) {
		if (this.consumer != null) {
			try {
				this.consumer.acknowledgeCumulative(message);
				return true;
			} catch (Exception e) {
				ExceptionUtil.getMessageFromException(e);
				logger.error("Error sending acknowledgement for " + message, e);
			}
		}
		return false;
	}

	@Override
	public void deliverNullPayloadTowardsTransport(Message message) throws Exception {
		Map<String, Object> metadata = message.getMetadataMap();
		if (metadata.containsKey(Message.CONTROL_TYPE)
				&& metadata.get(Message.CONTROL_TYPE).equals(Message.CONTROL_TYPE_ACK_UPTO)) {
			String messageIdPassed = (String) metadata.get(Message.MESSAGE_ID);
			if (this.sendAcknowledgement(this.messageIdMap.get(messageIdPassed))) {
				this.messageIdMap.remove(messageIdPassed);
			}
		}
	}

	/**
	 * Creates a {@link MessageListener} instance based on whether Auto
	 * Acknowledgment is enabled or not
	 * 
	 * @param chainManager
	 * @return {@link MessageListener}
	 */
	@SuppressWarnings("unchecked")
	private MessageListener<byte[]> createMessageListenerStrategy(PulsarChainManager chainManager) {

		if (chainManager.isAutoAckEnabled()) {

			logger.info(
					"Auto Acknowledgement Enabled. Messages will be automatically acknowledged once they are received by the transport");

			return (consumer, inputMessage) -> {

				Map<String, Object> inputDataMap = null;
				try (ByteArrayInputStream inputStream = new ByteArrayInputStream(inputMessage.getData());
						ObjectInputStream objectInputStream = new ObjectInputStream(inputStream)) {
					Object object = objectInputStream.readObject();
					inputDataMap = (Map<String, Object>) object;
					Message apamaMessage = new Message(inputDataMap);
					// Auto Acknowledged - No need to send the message id
					hostSide.sendBatchTowardsHost(Collections.singletonList(apamaMessage));
					consumedStatus.increment();
					managerConsumedStatus.increment();
					statusItem.setStatus(STATUS_ONLINE);
					sendAcknowledgement(inputMessage);
				} catch (ClassNotFoundException | IOException e) {
					ExceptionUtil.getMessageFromException(e);
					logger.error("Error Receiving message from Pulsar system.", e);
				}
			};
		} else {
			logger.info("Auto Acknowledgement Disabled. User has to explicitly send receive acknowledgements from EPL");

			return (consumer, inputMessage) -> {

				Map<String, Object> inputDataMap = null;
				try (ByteArrayInputStream inputStream = new ByteArrayInputStream(inputMessage.getData());
						ObjectInputStream objectInputStream = new ObjectInputStream(inputStream)) {
					Object object = objectInputStream.readObject();
					inputDataMap = (Map<String, Object>) object;

					String newMessageId = UUID.nameUUIDFromBytes(inputMessage.getMessageId().toByteArray()).toString();

					Message apamaMessage = new Message(inputDataMap);
					apamaMessage.putMetadataValue(Message.MESSAGE_ID, newMessageId);

					// If message count has reached the threshold, send an Ack_required message to
					// host
					if (this.messageCount.incrementAndGet() >= (this.chainManager.getAckRequestFrequency()
							* this.maxReceiverQueueSize)) {

						Message apamaAckRequired = new Message(null);
						apamaAckRequired.putMetadataValue(Message.CONTROL_TYPE, Message.CONTROL_TYPE_ACK_REQUIRED);
						apamaAckRequired.putMetadataValue(Message.MESSAGE_ID, newMessageId);
						hostSide.sendBatchTowardsHost(Arrays.asList(apamaMessage, apamaAckRequired));

						// Add this message id to UnAcknowledged message map.
						this.messageIdMap.put(newMessageId, inputMessage);
						this.messageCount.set(0);
					} else {
						hostSide.sendBatchTowardsHost(Collections.singletonList(apamaMessage));
					}
					consumedStatus.increment();
					managerConsumedStatus.increment();
					statusItem.setStatus(STATUS_ONLINE);

				} catch (ClassNotFoundException | IOException e) {
					ExceptionUtil.getMessageFromException(e);
					logger.error("Error Receiving message from Pulsar system.", e);
				}
			};
		}
	}

}
