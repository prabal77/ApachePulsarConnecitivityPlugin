/**
 * 
 */
package com.apama.transport;

import static com.softwareag.connectivity.StatusReporter.STATUS_FAILED;
import static com.softwareag.connectivity.StatusReporter.STATUS_ONLINE;
import static com.softwareag.connectivity.StatusReporter.STATUS_STARTING;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.pulsar.client.api.Producer;
import org.slf4j.Logger;

import com.apama.pulsarChainManager;
import com.apama.util.ExceptionUtil;
import com.softwareag.connectivity.AbstractSimpleTransport;
import com.softwareag.connectivity.Message;
import com.softwareag.connectivity.PluginConstructorParameters.TransportConstructorParameters;
import com.softwareag.connectivity.StatusReporter;
import com.softwareag.connectivity.StatusReporter.StatusItem;

/**
 * @author NANP
 *
 */
public class producerTransport extends AbstractSimpleTransport {

	private pulsarChainManager chainManager;
	private Producer<byte[]> producer;

	private final StatusItem managerProducedStatus;
	private final StatusItem producedStatus;
	private final StatusItem statusItem;
	private volatile AtomicBoolean shuttingDown = new AtomicBoolean(false);
	private volatile Lock lock = new ReentrantLock();

	public producerTransport(Logger logger, TransportConstructorParameters params, pulsarChainManager chainManager)
			throws IllegalArgumentException, Exception {
		super(logger, params);

		// Save the callee chainManager instance
		this.chainManager = chainManager;

		// Setup status monitoring and KPIs
		final String statusPrefix = String.format("%s.%s.%s", pulsarChainManager.CHANNEL_PREFIX_TO,
				chainManager.managerName, this.chainManager.getProducerPropertyMap().getOrDefault("topic", ""));

		statusItem = getStatusReporter().createStatusItem(statusPrefix + ".status", STATUS_STARTING);
		producedStatus = getStatusReporter().createStatusItem(statusPrefix + ".produced", 0);
		getStatusReporter().createStatusItem(statusPrefix + ".KPIs",
				String.join(",", Arrays.asList(producedStatus.getKey())));
		managerProducedStatus = chainManager.getProducedStatus();
	}

	@Override
	public void start() throws Exception {
		try {
			if (this.lock.tryLock() && !this.shuttingDown.get())
				this.producer = this.chainManager.getPulsarClient().newProducer()
						.loadConf(this.chainManager.getProducerPropertyMap()).create();

		} catch (Exception exception) {
			ExceptionUtil.propagateException(exception);
			statusItem.setStatus(StatusReporter.STATUS_FAILED);
			this.shuttingDown.set(true);
			throw exception;
		} finally {
			this.lock.unlock();
		}
		statusItem.setStatus(StatusReporter.STATUS_ONLINE);
		this.shuttingDown.set(false);
	}

	@Override
	public void deliverMessageTowardsTransport(Message apamaMessage) throws Exception {
		this.logger.trace("Message {} delivering to topic {}", apamaMessage.toString());
		// Don't send message if shutdown has been called
		if (!this.shuttingDown.get()) {

			try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
					ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {

				objectOutputStream.writeObject(apamaMessage.getPayload());
				objectOutputStream.flush();
				byte[] byteArray = byteArrayOutputStream.toByteArray();
				this.producer.sendAsync(byteArray).thenAccept(messageId -> {
					this.logger.debug("Message successfully sent. Message Id ", messageId);
					this.managerProducedStatus.increment();
					this.producedStatus.increment();
				});
				statusItem.setStatus(STATUS_ONLINE);
			} catch (IOException e) {
				statusItem.setStatus(STATUS_FAILED);
				this.logger.error("Record delivery failed with exception: {}", e.getMessage());
			}
		}
	}

	@Override
	public void shutdown() throws Exception {
		try {
			this.lock.lockInterruptibly();
			if (this.producer != null)
				this.producer.closeAsync().exceptionally(ex -> {
					ExceptionUtil.getMessageFromException(ex);
					statusItem.setStatus(StatusReporter.STATUS_FAILED);
					throw new RuntimeException("Unable to shut down Pulsar producers", ex);
				});
		} finally {
			this.lock.unlock();
		}
	}

}
