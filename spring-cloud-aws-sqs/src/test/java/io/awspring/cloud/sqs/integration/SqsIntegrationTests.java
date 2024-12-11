/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.awspring.cloud.sqs.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.CompletableFutures;
import io.awspring.cloud.sqs.MessageHeaderUtils;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration;
import io.awspring.cloud.sqs.config.SqsListenerConfigurer;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.BatchVisibility;
import io.awspring.cloud.sqs.listener.ContainerComponentFactory;
import io.awspring.cloud.sqs.listener.MessageListenerContainer;
import io.awspring.cloud.sqs.listener.QueueAttributes;
import io.awspring.cloud.sqs.listener.SemaphoreBackPressureHandler;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import io.awspring.cloud.sqs.listener.SqsContainerOptionsBuilder;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer;
import io.awspring.cloud.sqs.listener.StandardSqsComponentFactory;
import io.awspring.cloud.sqs.listener.Visibility;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementExecutor;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementResultCallback;
import io.awspring.cloud.sqs.listener.acknowledgement.BatchAcknowledgement;
import io.awspring.cloud.sqs.listener.acknowledgement.SqsAcknowledgementExecutor;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import io.awspring.cloud.sqs.listener.errorhandler.AsyncErrorHandler;
import io.awspring.cloud.sqs.listener.interceptor.AsyncMessageInterceptor;
import io.awspring.cloud.sqs.listener.sink.MessageSink;
import io.awspring.cloud.sqs.listener.source.AbstractSqsMessageSource;
import io.awspring.cloud.sqs.listener.source.MessageSource;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for SQS integration.
 *
 * @author Tomaz Fernandes
 * @author Mikhail Strokov
 * @author Michael Sosa
 * @author gustavomonarin
 */
@SpringBootTest
@TestPropertySource(properties = { "property.one=1", "property.five.seconds=5s",
		"receives.message.queue.name=" + SqsIntegrationTests.RECEIVES_MESSAGE_QUEUE_NAME,
		"low.resource.factory.name=" + SqsIntegrationTests.LOW_RESOURCE_FACTORY })
class SqsIntegrationTests extends BaseSqsIntegrationTest {

	private static final Logger logger = LoggerFactory.getLogger(SqsIntegrationTests.class);

	static final String RECEIVES_MESSAGE_QUEUE_NAME = "receives_message_test_queue";

	static final String RECEIVES_MESSAGE_BATCH_QUEUE_NAME = "receives_message_batch_test_queue";

	static final String RECEIVES_MESSAGE_ASYNC_QUEUE_NAME = "receives_message_async_test_queue";

	static final String DOES_NOT_ACK_ON_ERROR_QUEUE_NAME = "does_not_ack_test_queue";

	static final String DOES_NOT_ACK_ON_ERROR_ASYNC_QUEUE_NAME = "does_not_ack_async_test_queue";

	static final String DOES_NOT_ACK_ON_ERROR_BATCH_QUEUE_NAME = "does_not_ack_batch_test_queue";

	static final String DOES_NOT_ACK_ON_ERROR_BATCH_ASYNC_QUEUE_NAME = "does_not_ack_batch_async_test_queue";

	static final String RESOLVES_PARAMETER_TYPES_QUEUE_NAME = "resolves_parameter_type_test_queue";

	static final String MANUALLY_START_CONTAINER = "manually_start_container_test_queue";

	static final String MANUALLY_CREATE_CONTAINER_QUEUE_NAME = "manually_create_container_test_queue";

	static final String MANUALLY_CREATE_INACTIVE_CONTAINER_QUEUE_NAME = "manually_create_inactive_container_test_queue";

	static final String MANUALLY_CREATE_FACTORY_QUEUE_NAME = "manually_create_factory_test_queue";

	static final String CONSUMES_ONE_MESSAGE_AT_A_TIME_QUEUE_NAME = "consumes_one_message_test_queue";

	static final String MAX_CONCURRENT_MESSAGES_QUEUE_NAME = "max_concurrent_messages_test_queue";

	static final String LOW_RESOURCE_FACTORY = "lowResourceFactory";

	static final String MANUAL_ACK_FACTORY = "manualAcknowledgementFactory";

	static final String MANUAL_ACK_BATCH_FACTORY = "manualAcknowledgementBatchFactory";

	static final String ACK_AFTER_SECOND_ERROR_FACTORY = "ackAfterSecondErrorFactory";

	static final String BACK_PRESSURE_LIMITER_FACTORY = "backPressureLimiterSqsListenerContainerFactory";

	@BeforeAll
	static void beforeTests() {
		SqsAsyncClient client = createAsyncClient();
		CompletableFuture.allOf(createQueue(client, RECEIVES_MESSAGE_QUEUE_NAME),
				createQueue(client, DOES_NOT_ACK_ON_ERROR_QUEUE_NAME,
						singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "1")),
				createQueue(client, DOES_NOT_ACK_ON_ERROR_ASYNC_QUEUE_NAME,
						singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "1")),
				createQueue(client, DOES_NOT_ACK_ON_ERROR_BATCH_QUEUE_NAME,
						singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "1")),
				createQueue(client, DOES_NOT_ACK_ON_ERROR_BATCH_ASYNC_QUEUE_NAME,
						singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "1")),
				createQueue(client, RECEIVES_MESSAGE_ASYNC_QUEUE_NAME),
				createQueue(client, RECEIVES_MESSAGE_BATCH_QUEUE_NAME),
				createQueue(client, RESOLVES_PARAMETER_TYPES_QUEUE_NAME,
						singletonMap(QueueAttributeName.VISIBILITY_TIMEOUT, "20")),
				createQueue(client, MANUALLY_CREATE_CONTAINER_QUEUE_NAME),
				createQueue(client, MANUALLY_CREATE_INACTIVE_CONTAINER_QUEUE_NAME),
				createQueue(client, MANUALLY_CREATE_FACTORY_QUEUE_NAME),
				createQueue(client, CONSUMES_ONE_MESSAGE_AT_A_TIME_QUEUE_NAME),
				createQueue(client, MAX_CONCURRENT_MESSAGES_QUEUE_NAME)).join();
	}

	@Autowired
	LatchContainer latchContainer;

	@Autowired
	SqsTemplate sqsTemplate;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	@Qualifier("inactiveContainer")
	MessageListenerContainer<Object> inactiveMessageListenerContainer;

	@Test
	void receivesMessage() throws Exception {
		String messageBody = "receivesMessage-payload";
		sqsTemplate.send(RECEIVES_MESSAGE_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", RECEIVES_MESSAGE_QUEUE_NAME, messageBody);
		assertThat(latchContainer.receivesMessageLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.invocableHandlerMethodLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.acknowledgementCallbackSuccessLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void receivesMessageBatch() throws Exception {
		List<Message<String>> messages = create10Messages("receivesMessageBatch");
		sqsTemplate.sendMany(RECEIVES_MESSAGE_BATCH_QUEUE_NAME, messages);
		logger.debug("Sent 10 messages to queue {}", RECEIVES_MESSAGE_BATCH_QUEUE_NAME);
		await().untilAsserted(() -> {
			// ensure that first batch was processed more than once
			assertThat(latchContainer.receivesMessageBatchLatch.getCount()).isLessThan(10);
		});
		assertThat(latchContainer.acknowledgementCallbackBatchLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void receivesMessageAsync() throws Exception {
		String messageBody = "receivesMessageAsync-payload";
		sqsTemplate.send(RECEIVES_MESSAGE_ASYNC_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", RECEIVES_MESSAGE_ASYNC_QUEUE_NAME, messageBody);
		assertThat(latchContainer.receivesMessageAsyncLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void doesNotAckOnError() throws Exception {
		String messageBody = "doesNotAckOnError-payload";
		sqsTemplate.send(DOES_NOT_ACK_ON_ERROR_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", DOES_NOT_ACK_ON_ERROR_QUEUE_NAME, messageBody);
		assertThat(latchContainer.doesNotAckLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.acknowledgementCallbackErrorLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void doesNotAckOnErrorAsync() throws Exception {
		String messageBody = "doesNotAckOnErrorAsync-payload";
		sqsTemplate.send(DOES_NOT_ACK_ON_ERROR_ASYNC_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", DOES_NOT_ACK_ON_ERROR_ASYNC_QUEUE_NAME,
				messageBody);
		assertThat(latchContainer.doesNotAckAsyncLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void doesNotAckOnErrorBatch() throws Exception {
		List<Message<String>> messages = IntStream.range(0, 10)
				.mapToObj(index -> "doesNotAckOnErrorBatch-payload-" + index)
				.map(payload -> MessageBuilder.withPayload(payload).build()).collect(Collectors.toList());
		sqsTemplate.sendManyAsync(DOES_NOT_ACK_ON_ERROR_BATCH_QUEUE_NAME, messages);
		logger.debug("Sent messages to queue {} with messages {}", DOES_NOT_ACK_ON_ERROR_BATCH_QUEUE_NAME, messages);
		assertThat(latchContainer.doesNotAckBatchLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void doesNotAckOnErrorBatchAsync() throws Exception {
		List<Message<String>> messages = IntStream.range(0, 10)
				.mapToObj(index -> "doesNotAckOnErrorBatchAsync-payload-" + index)
				.map(payload -> MessageBuilder.withPayload(payload).build()).collect(Collectors.toList());
		sqsTemplate.sendManyAsync(DOES_NOT_ACK_ON_ERROR_BATCH_ASYNC_QUEUE_NAME, messages);
		logger.debug("Sent messages to queue {} with messages {}", DOES_NOT_ACK_ON_ERROR_BATCH_ASYNC_QUEUE_NAME,
				messages);
		assertThat(latchContainer.doesNotAckBatchAsyncLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void resolvesManyParameterTypes() throws Exception {
		String messageBody = "many-parameter-types-payload";
		sqsTemplate.send(RESOLVES_PARAMETER_TYPES_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", RESOLVES_PARAMETER_TYPES_QUEUE_NAME, messageBody);
		assertThat(latchContainer.manyParameterTypesLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manyParameterTypesSecondLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void manuallyCreatesContainer() throws Exception {
		String messageBody = "Testing manually creates container";
		sqsTemplate.send(MANUALLY_CREATE_CONTAINER_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", MANUALLY_CREATE_CONTAINER_QUEUE_NAME, messageBody);
		assertThat(latchContainer.manuallyCreatedContainerLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void manuallyCreatesInactiveContainer() throws Exception {
		String messageBody = "Testing manually creates inactive container";
		assertThat(inactiveMessageListenerContainer.isRunning()).isFalse();
		sqsTemplate.send(MANUALLY_CREATE_INACTIVE_CONTAINER_QUEUE_NAME, messageBody);
		inactiveMessageListenerContainer.start();
		logger.debug("Sent message to queue {} with messageBody {}", MANUALLY_CREATE_INACTIVE_CONTAINER_QUEUE_NAME,
				messageBody);
		assertThat(latchContainer.manuallyInactiveCreatedContainerLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	// @formatter:off
	@Test
	void manuallyStartsContainerAndChangesComponent() throws Exception {
		SqsMessageListenerContainer<?> container = SqsMessageListenerContainer
			.builder()
			.sqsAsyncClient(createAsyncClient())
			.queueNames(MANUALLY_START_CONTAINER)
			.messageListener(msg -> latchContainer.manuallyStartedContainerLatch.countDown())
			.configure(options -> options
					.maxDelayBetweenPolls(Duration.ofSeconds(1))
					.pollTimeout(Duration.ofSeconds(3)))
			.build();
		container.start();
		String messageBody1 = "MyTest";
		sqsTemplate.send(MANUALLY_START_CONTAINER, messageBody1);
		logger.debug("Sent message to queue {} with messageBody {}", MANUALLY_START_CONTAINER, messageBody1);
		assertThat(latchContainer.manuallyStartedContainerLatch.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
		container.setMessageListener(msg -> latchContainer.manuallyStartedContainerLatch2.countDown());
		SqsContainerOptionsBuilder builder = container.getContainerOptions().toBuilder();
		builder.acknowledgementMode(AcknowledgementMode.ALWAYS);
		container.configure(options -> options.fromBuilder(builder));
		container.start();
		String messageBody2 = "MyTest2";
		sqsTemplate.send(MANUALLY_START_CONTAINER, messageBody2);
		logger.debug("Sent message to queue {} with messageBody {}", MANUALLY_START_CONTAINER, messageBody2);
		assertThat(latchContainer.manuallyStartedContainerLatch2.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
	}
	// @formatter:on

	@Test
	void manuallyCreatesFactory() throws Exception {
		String messageBody = "Testing manually creates factory";
		sqsTemplate.send(MANUALLY_CREATE_FACTORY_QUEUE_NAME, messageBody);
		logger.debug("Sent message to queue {} with messageBody {}", MANUALLY_CREATE_FACTORY_QUEUE_NAME, messageBody);
		assertThat(latchContainer.manuallyCreatedFactoryLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manuallyCreatedFactorySourceFactoryLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(latchContainer.manuallyCreatedFactorySinkLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void consumesOneMessageAtATime() throws Exception {
		IntStream.range(0, 10).forEach(index -> {
			List<Message<String>> messages = create10Messages("consumesOneMessageAtATime");
			sqsTemplate.sendMany(CONSUMES_ONE_MESSAGE_AT_A_TIME_QUEUE_NAME, messages);
		});
		logger.debug("Sent 10 messages to queue {}", CONSUMES_ONE_MESSAGE_AT_A_TIME_QUEUE_NAME);
		var latch = new CountDownLatch(100);
		var container = SqsMessageListenerContainer.builder().sqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient())
				.queueNames(CONSUMES_ONE_MESSAGE_AT_A_TIME_QUEUE_NAME).configure(options -> options
						.pollTimeout(Duration.ofSeconds(1)).maxConcurrentMessages(1).maxMessagesPerPoll(1))
				.messageListener(msg -> latch.countDown()).build();
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
	}

	private List<Message<String>> create10Messages(String testName) {
		return IntStream.range(0, 10).mapToObj(index -> testName + "-payload-" + index)
				.map(payload -> MessageBuilder.withPayload(payload).build()).collect(Collectors.toList());
	}

	@Test
	void maxConcurrentMessages() {
		List<Message<String>> messages1 = IntStream.range(0, 10)
				.mapToObj(index -> "maxConcurrentMessages-payload-" + index)
				.map(payload -> MessageBuilder.withPayload(payload).build()).collect(Collectors.toList());
		List<Message<String>> messages2 = IntStream.range(10, 20)
				.mapToObj(index -> "maxConcurrentMessages-payload-" + index)
				.map(payload -> MessageBuilder.withPayload(payload).build()).collect(Collectors.toList());
		sqsTemplate.sendManyAsync(MAX_CONCURRENT_MESSAGES_QUEUE_NAME, messages1);
		sqsTemplate.sendManyAsync(MAX_CONCURRENT_MESSAGES_QUEUE_NAME, messages2);
		logger.debug("Sent messages to queue {} with messages {} and {}", MAX_CONCURRENT_MESSAGES_QUEUE_NAME, messages1,
				messages2);
		assertDoesNotThrow(() -> latchContainer.maxConcurrentMessagesBarrier.await(10, TimeUnit.SECONDS));
	}

	static final class Limiter implements SemaphoreBackPressureHandler.BackPressureLimiter {
		private final AtomicInteger limit;

		Limiter(int max) {
			limit = new AtomicInteger(max);
		}

		public void adjustLimit(int value) {
			logger.info("adjusting limit by {} to {}", value, limit.addAndGet(value));
		}

		public void setLimit(int value) {
			logger.info("adjusting limit from {} to {}", limit.get(), value);
			limit.set(value);
		}

		@Override
		public int limit() {
			return Math.max(0, limit.get());
		}
	}

	@ParameterizedTest
	@CsvSource({"2,2", "4,4", "5,5", "20,5"})
	void staticBackPressureLimitShouldCapQueueProcessingCapacity(int staticLimit, int expectedMaxConcurrentRequests) throws Exception {
		AtomicInteger concurrentRequest = new AtomicInteger();
		AtomicInteger maxConcurrentRequest = new AtomicInteger();
		Limiter limiter = new Limiter(staticLimit);
		String queueName = "BACK_PRESSURE_LIMITER_QUEUE_NAME_STATIC_LIMIT_" + staticLimit;
		IntStream.range(0, 10).forEach(index -> {
			List<Message<String>> messages = create10Messages("staticBackPressureLimit" + staticLimit);
			sqsTemplate.sendMany(queueName, messages);
		});
		logger.debug("Sent 100 messages to queue {}", queueName);
		var latch = new CountDownLatch(100);
		var container = SqsMessageListenerContainer.builder()
			.sqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient())
			.queueNames(queueName)
			.configure(options -> options
				.pollTimeout(Duration.ofSeconds(1)).maxConcurrentMessages(5).maxMessagesPerPoll(5)
				.backPressureLimiter(limiter))
			.messageListener(msg -> {
				int concurrentRqs = concurrentRequest.incrementAndGet();
				maxConcurrentRequest.updateAndGet(max -> Math.max(max, concurrentRqs));
				sleep(50L);
				logger.debug("concurrent rq {}, max concurrent rq {}, latch count {}",
					concurrentRequest.get(), maxConcurrentRequest.get(), latch.getCount());
				latch.countDown();
				concurrentRequest.decrementAndGet();
			}).build();
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(maxConcurrentRequest.get()).isEqualTo(expectedMaxConcurrentRequests);
		container.stop();
	}

	@Test
	void zeroBackPressureLimitShouldStopQueueProcessing() throws Exception {
		AtomicInteger concurrentRequest = new AtomicInteger();
		AtomicInteger maxConcurrentRequest = new AtomicInteger();
		Limiter limiter = new Limiter(0);
		String queueName = "BACK_PRESSURE_LIMITER_QUEUE_NAME_STATIC_LIMIT_0";
		IntStream.range(0, 10).forEach(index -> {
			List<Message<String>> messages = create10Messages("staticBackPressureLimit0");
			sqsTemplate.sendMany(queueName, messages);
		});
		logger.debug("Sent 100 messages to queue {}", queueName);
		var latch = new CountDownLatch(100);
		var container = SqsMessageListenerContainer.builder()
			.sqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient())
			.queueNames(queueName)
			.configure(options -> options
				.pollTimeout(Duration.ofSeconds(1)).maxConcurrentMessages(5).maxMessagesPerPoll(5)
				.backPressureLimiter(limiter))
			.messageListener(msg -> {
				int concurrentRqs = concurrentRequest.incrementAndGet();
				maxConcurrentRequest.updateAndGet(max -> Math.max(max, concurrentRqs));
				sleep(50L);
				logger.debug("concurrent rq {}, max concurrent rq {}, latch count {}",
					concurrentRequest.get(), maxConcurrentRequest.get(), latch.getCount());
				latch.countDown();
				concurrentRequest.decrementAndGet();
			}).build();
		container.start();
		assertThat(latch.await(2, TimeUnit.SECONDS)).isFalse();
		assertThat(maxConcurrentRequest.get()).isZero();
		assertThat(latch.getCount()).isEqualTo(100L);
		container.stop();
	}

	@Test
	void changeInBackPressureLimitShouldAdaptQueueProcessingCapacity() throws Exception {
		AtomicInteger concurrentRequest = new AtomicInteger();
		AtomicInteger maxConcurrentRequest = new AtomicInteger();
		Limiter limiter = new Limiter(5);
		String queueName = "BACK_PRESSURE_LIMITER_QUEUE_NAME_ADAPTIVE_LIMIT";
		int nbMessages = 130;
		IntStream.range(0, nbMessages / 10).forEach(index -> {
			List<Message<String>> messages = create10Messages("adaptiveBackPressureLimit");
			sqsTemplate.sendMany(queueName, messages);
		});
		logger.debug("Sent {} messages to queue {}", nbMessages, queueName);
		var latch = new CountDownLatch(nbMessages);
		var controlSemaphore = new Semaphore(0);
		var advanceSemaphore = new Semaphore(0);
		var container = SqsMessageListenerContainer.builder()
			.sqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient())
			.queueNames(queueName)
			.configure(options -> options
				.pollTimeout(Duration.ofSeconds(1)).maxConcurrentMessages(5).maxMessagesPerPoll(5)
				.backPressureLimiter(limiter))
			.messageListener(msg -> {
				try {
					controlSemaphore.acquire();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				int concurrentRqs = concurrentRequest.incrementAndGet();
				maxConcurrentRequest.updateAndGet(max -> Math.max(max, concurrentRqs));
				latch.countDown();
				logger.debug("concurrent rq {}, max concurrent rq {}, latch count {}",
					concurrentRequest.get(), maxConcurrentRequest.get(), latch.getCount());
				sleep(100L);
				concurrentRequest.decrementAndGet();
				advanceSemaphore.release();
			}).build();
		class Controller {
			private final Semaphore advanceSemaphore;
			private final Semaphore controlSemaphore;
			private final Limiter limiter;
			private final AtomicInteger maxConcurrentRequest;

			Controller(Semaphore advanceSemaphore, Semaphore controlSemaphore, Limiter limiter, AtomicInteger maxConcurrentRequest) {
				this.advanceSemaphore = advanceSemaphore;
				this.controlSemaphore = controlSemaphore;
				this.limiter = limiter;
				this.maxConcurrentRequest = maxConcurrentRequest;
			}

			public void updateLimit(int newLimit) {
				limiter.setLimit(newLimit);
			}

			void updateLimitAndWaitForReset(int newLimit) throws InterruptedException {
				updateLimit(newLimit);
				int atLeastTwoPollingCycles = 2 * 5;
				controlSemaphore.release(atLeastTwoPollingCycles);
				waitForAdvance(atLeastTwoPollingCycles);
				maxConcurrentRequest.set(0);
			}

			void advance(int permits) {
				controlSemaphore.release(permits);
			}

			void waitForAdvance(int permits) throws InterruptedException {
				assertThat(advanceSemaphore.tryAcquire(permits, 5, TimeUnit.SECONDS))
					.withFailMessage(() -> "Waiting for %d permits timed out. Only %d permits available"
						.formatted(permits, advanceSemaphore.availablePermits()))
					.isTrue();
			}
		}
		var controller = new Controller(advanceSemaphore, controlSemaphore, limiter, maxConcurrentRequest);
		try {
			container.start();

			controller.advance(20);
			// TODO, currently this works, but having this discrepancy is not ideal of 5 is not ideal. This is due to
			//  the fact that the message listener first advance, then waits for the semaphore to be released. We could
			//  change the order of these operations to harmonize the behavior, which in theory would allow to get rid
			//  of this discrepancy, but not sure I have the energy tonight. A side effect could be that we need to wait
			//  for 2 cycles to see the effect of the change in limit, which is not ideal either.
			controller.waitForAdvance(20);
			assertThat(controller.maxConcurrentRequest.get()).isEqualTo(5); // not limiting queue processing capacity
			controller.updateLimitAndWaitForReset(2);
			controller.advance(20);

			controller.waitForAdvance(20);
			assertThat(controller.maxConcurrentRequest.get()).isEqualTo(2); // limiting queue processing capacity
			controller.updateLimitAndWaitForReset(7);
			controller.advance(20);

			controller.waitForAdvance(20);
			assertThat(controller.maxConcurrentRequest.get()).isEqualTo(5); // not limiting queue processing capacity
			controller.updateLimitAndWaitForReset(3);
			controller.advance(20);

			controller.waitForAdvance(20);
			assertThat(controller.maxConcurrentRequest.get()).isEqualTo(3);
			controller.updateLimit(0); // stopping processing of the queue
			controller.advance(20);
			assertThat(advanceSemaphore.tryAcquire(10, 5, TimeUnit.SECONDS))
				.withFailMessage("Acquiring semaphore should have timed out as limit was set to 0")
				.isFalse();

			controller.updateLimit(6); // resume queue processing

			controller.waitForAdvance(20);
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(controller.maxConcurrentRequest.get()).isEqualTo(5);
		} finally {
			container.stop();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static class ReceivesMessageListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = "${receives.message.queue.name}", pollTimeoutSeconds = "${property.one}", maxMessagesPerPoll = "${property.one}", maxConcurrentMessages = "${missing.property:5}", id = "receivesMessageContainer")
		void listen(String message) {
			logger.debug("Received message in Listener Method: " + message);
			latchContainer.receivesMessageLatch.countDown();
		}
	}

	static class ReceivesMessageBatchListener {

		@Autowired
		LatchContainer latchContainer;

		AtomicBoolean firstPass = new AtomicBoolean(true);

		@SqsListener(queueNames = RECEIVES_MESSAGE_BATCH_QUEUE_NAME, factory = MANUAL_ACK_BATCH_FACTORY, id = "receivesMessageBatchListener")
		CompletableFuture<Void> listen(List<String> messages, BatchAcknowledgement<String> acknowledgement,
				BatchVisibility visibility) throws Exception {
			logger.debug("Received messages in listener: " + messages);

			if (firstPass.compareAndSet(true, false)) {
				visibility.changeTo(1);
				Thread.sleep(1000);
			}
			else {
				acknowledgement.acknowledge();
			}

			messages.forEach(msg -> latchContainer.receivesMessageBatchLatch.countDown());
			return CompletableFuture.completedFuture(null);
		}
	}

	static class ReceivesMessageAsyncListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = RECEIVES_MESSAGE_ASYNC_QUEUE_NAME, factory = "${low.resource.factory.name}", id = "receivesMessageAsyncContainer")
		CompletableFuture<Void> listen(String message) {
			logger.debug("Received message in Listener Method: " + message);
			latchContainer.receivesMessageAsyncLatch.countDown();
			return CompletableFuture.completedFuture(null);
		}
	}

	static class DoesNotAckOnErrorListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = DOES_NOT_ACK_ON_ERROR_QUEUE_NAME, factory = ACK_AFTER_SECOND_ERROR_FACTORY, id = "does-not-ack")
		void listen(String message, @Header(SqsHeaders.SQS_QUEUE_NAME_HEADER) String queueName) {
			logger.debug("Received message {} from queue {}", message, queueName);
			latchContainer.doesNotAckLatch.countDown();
			throw new RuntimeException("Expected exception from does-not-ack");
		}
	}

	static class DoesNotAckOnErrorAsyncListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = DOES_NOT_ACK_ON_ERROR_ASYNC_QUEUE_NAME, messageVisibilitySeconds = "#{1}", factory = ACK_AFTER_SECOND_ERROR_FACTORY, id = "does-not-ack-async")
		CompletableFuture<Void> listen(String message, @Header(SqsHeaders.SQS_QUEUE_NAME_HEADER) String queueName) {
			logger.debug("Received message {} from queue {}", message, queueName);
			latchContainer.doesNotAckAsyncLatch.countDown();
			return CompletableFutures.failedFuture(new RuntimeException("Expected exception from does-not-ack-async"));
		}
	}

	static class DoesNotAckOnErrorBatchListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = DOES_NOT_ACK_ON_ERROR_BATCH_QUEUE_NAME, messageVisibilitySeconds = "2", factory = ACK_AFTER_SECOND_ERROR_FACTORY, id = "does-not-ack-batch")
		void listen(List<Message<String>> messages) {
			logger.debug("Received messages {} from queue {}", MessageHeaderUtils.getId(messages),
					messages.get(0).getHeaders().get(SqsHeaders.SQS_QUEUE_NAME_HEADER));
			messages.forEach(msg -> latchContainer.doesNotAckBatchLatch.countDown());
			throw new RuntimeException("Expected exception from does-not-ack-batch");
		}
	}

	static class DoesNotAckOnErrorAsyncBatchListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = DOES_NOT_ACK_ON_ERROR_BATCH_ASYNC_QUEUE_NAME, factory = ACK_AFTER_SECOND_ERROR_FACTORY, id = "does-not-ack-batch-async")
		CompletableFuture<Void> listen(List<Message<String>> messages) {
			logger.debug("Received messages {} from queue {}", MessageHeaderUtils.getId(messages),
					messages.get(0).getHeaders().get(SqsHeaders.SQS_QUEUE_NAME_HEADER));
			messages.forEach(msg -> latchContainer.doesNotAckBatchAsyncLatch.countDown());
			return CompletableFutures
					.failedFuture(new RuntimeException("Expected exception from does-not-ack-batch-async"));
		}
	}

	static class ResolvesParameterTypesListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = RESOLVES_PARAMETER_TYPES_QUEUE_NAME, factory = MANUAL_ACK_FACTORY, id = "resolves-parameter")
		void listen(Message<String> message, MessageHeaders headers, Acknowledgement ack, Visibility visibility,
				QueueAttributes queueAttributes, software.amazon.awssdk.services.sqs.model.Message originalMessage)
				throws Exception {
			Assert.notNull(headers, "Received null MessageHeaders");
			Assert.notNull(ack, "Received null Acknowledgement");
			Assert.notNull(visibility, "Received null Visibility");
			Assert.notNull(queueAttributes, "Received null QueueAttributes");
			Assert.notNull(originalMessage, "Received null software.amazon.awssdk.services.sqs.model.Message");
			Assert.notNull(message, "Received null message");
			logger.debug("Received message in Listener Method: " + message);
			Assert.notNull(queueAttributes.getQueueAttribute(QueueAttributeName.QUEUE_ARN),
					"QueueArn attribute not found");

			visibility.changeTo(1);

			// Verify VisibilityTimeout extension
			latchContainer.manyParameterTypesLatch.countDown();
			if (latchContainer.manyParameterTypesSecondLatch.getCount() == 1) {
				ack.acknowledge();
			}
			latchContainer.manyParameterTypesSecondLatch.countDown();
			Thread.sleep(1000);
		}
	}

	static class MaxConcurrentMessagesListener {

		@Autowired
		LatchContainer latchContainer;

		@SqsListener(queueNames = MAX_CONCURRENT_MESSAGES_QUEUE_NAME, maxMessagesPerPoll = "10", maxConcurrentMessages = "20", id = "max-concurrent-messages")
		void listen(String message) throws BrokenBarrierException, InterruptedException {
			logger.debug("Received message in Listener Method: " + message);
			latchContainer.maxConcurrentMessagesBarrier.await();
		}
	}

	static class LatchContainer {

		final CountDownLatch receivesMessageLatch = new CountDownLatch(1);
		final CountDownLatch receivesMessageBatchLatch = new CountDownLatch(20);
		final CountDownLatch receivesMessageAsyncLatch = new CountDownLatch(1);
		final CountDownLatch doesNotAckLatch = new CountDownLatch(2);
		final CountDownLatch doesNotAckAsyncLatch = new CountDownLatch(2);
		final CountDownLatch doesNotAckBatchLatch = new CountDownLatch(20);
		final CountDownLatch doesNotAckBatchAsyncLatch = new CountDownLatch(20);
		final CountDownLatch interceptorLatch = new CountDownLatch(2);
		final CountDownLatch manyParameterTypesLatch = new CountDownLatch(1);
		final CountDownLatch manyParameterTypesSecondLatch = new CountDownLatch(2);
		final CountDownLatch manuallyCreatedContainerLatch = new CountDownLatch(1);
		final CountDownLatch manuallyStartedContainerLatch = new CountDownLatch(1);
		final CountDownLatch manuallyStartedContainerLatch2 = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactorySourceFactoryLatch = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactorySinkLatch = new CountDownLatch(1);
		final CountDownLatch manuallyCreatedFactoryLatch = new CountDownLatch(1);
		final CountDownLatch invocableHandlerMethodLatch = new CountDownLatch(1);
		final CountDownLatch acknowledgementCallbackSuccessLatch = new CountDownLatch(1);
		final CountDownLatch acknowledgementCallbackBatchLatch = new CountDownLatch(1);
		final CountDownLatch acknowledgementCallbackErrorLatch = new CountDownLatch(1);
		final CountDownLatch manuallyInactiveCreatedContainerLatch = new CountDownLatch(1);
		final CyclicBarrier maxConcurrentMessagesBarrier = new CyclicBarrier(21);

	}

	@Import(SqsBootstrapConfiguration.class)
	@Configuration
	static class SQSConfiguration {

		// @formatter:off
		@Bean
		public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.acknowledgementResultCallback(getAcknowledgementResultCallback())
				.configure(options -> options
					.maxDelayBetweenPolls(Duration.ofSeconds(5))
					.queueAttributeNames(Collections.singletonList(QueueAttributeName.QUEUE_ARN))
					.pollTimeout(Duration.ofSeconds(5)))
				.build();
		}

		@Bean(name = LOW_RESOURCE_FACTORY)
		public SqsMessageListenerContainerFactory<Object> lowResourceFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.configure(options -> options
					.maxConcurrentMessages(1)
					.pollTimeout(Duration.ofSeconds(5))
					.maxMessagesPerPoll(1)
					.maxDelayBetweenPolls(Duration.ofSeconds(5)))
				.messageInterceptor(testInterceptor())
				.messageInterceptor(testInterceptor())
				.errorHandler(testErrorHandler())
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.build();
		}

		@Bean(name = ACK_AFTER_SECOND_ERROR_FACTORY)
		public SqsMessageListenerContainerFactory<Object> ackAfterSecondErrorFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.configure(options -> options
					.maxConcurrentMessages(10)
					.pollTimeout(Duration.ofSeconds(10))
					.maxMessagesPerPoll(10)
					.maxDelayBetweenPolls(Duration.ofSeconds(1)))
				.messageInterceptor(testInterceptor())
				.messageInterceptor(testInterceptor())
				.containerComponentFactories(getExceptionThrowingAckExecutor())
				.acknowledgementResultCallback(getAcknowledgementResultCallback())
				.errorHandler(testErrorHandler())
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.build();
		}

		@Bean(name = BACK_PRESSURE_LIMITER_FACTORY)
		public SqsMessageListenerContainerFactory<Object> backPressureLimiterSqsListenerContainerFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.acknowledgementResultCallback(getAcknowledgementResultCallback())
				.configure(options -> options
					.maxDelayBetweenPolls(Duration.ofSeconds(5))
					.queueAttributeNames(Collections.singletonList(QueueAttributeName.QUEUE_ARN))
					.pollTimeout(Duration.ofSeconds(5)))
				.build();
		}

		private List<ContainerComponentFactory<Object, SqsContainerOptions>> getExceptionThrowingAckExecutor() {
			return Collections.singletonList(new StandardSqsComponentFactory<Object>() {
				@Override
				public MessageSource<Object> createMessageSource(SqsContainerOptions options) {
					return new AbstractSqsMessageSource<Object>() {
						@Override
						protected AcknowledgementExecutor<Object> createAcknowledgementExecutorInstance() {
							return new SqsAcknowledgementExecutor<Object>() {

								final AtomicBoolean hasThrown = new AtomicBoolean();

								@Override
								public CompletableFuture<Void> execute(Collection<Message<Object>> messagesToAck) {
									if (MessageHeaderUtils
										.getHeaderAsString(messagesToAck.iterator().next(), SqsHeaders.SQS_QUEUE_NAME_HEADER).equals(DOES_NOT_ACK_ON_ERROR_QUEUE_NAME)
										&& hasThrown.compareAndSet(false, true)) {
										return CompletableFutures.failedFuture(new RuntimeException("Expected acknowledgement exception for " + DOES_NOT_ACK_ON_ERROR_QUEUE_NAME));
									}
									return super.execute(messagesToAck);
								}
							};
						}
					};
				}
			});
		}

		@Bean(name = MANUAL_ACK_FACTORY)
		public SqsMessageListenerContainerFactory<Object> manualAcknowledgementFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.configure(options -> options
					.acknowledgementMode(AcknowledgementMode.MANUAL)
					.maxConcurrentMessages(1)
					.pollTimeout(Duration.ofSeconds(3))
					.maxMessagesPerPoll(1)
					.queueAttributeNames(Collections.singletonList(QueueAttributeName.QUEUE_ARN))
					.maxDelayBetweenPolls(Duration.ofSeconds(1)))
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.acknowledgementResultCallback(new AcknowledgementResultCallback<Object>() {
					@Override
					public void onSuccess(Collection<Message<Object>> messages) {
						if (RECEIVES_MESSAGE_BATCH_QUEUE_NAME.equals(MessageHeaderUtils.getHeaderAsString(messages.iterator().next(),
							SqsHeaders.SQS_QUEUE_NAME_HEADER))) {
							latchContainer.acknowledgementCallbackBatchLatch.countDown();
						}
					}
				})
				.build();
		}

		@Bean(name = MANUAL_ACK_BATCH_FACTORY)
		public SqsMessageListenerContainerFactory<Object> manualAcknowledgementBatchFactory() {
			return SqsMessageListenerContainerFactory
				.builder()
				.configure(options -> options
					.acknowledgementMode(AcknowledgementMode.MANUAL)
					.maxConcurrentMessages(10)
					.pollTimeout(Duration.ofSeconds(10))
					.maxMessagesPerPoll(10)
					.queueAttributeNames(Collections.singletonList(QueueAttributeName.QUEUE_ARN))
					.maxDelayBetweenPolls(Duration.ofSeconds(10)))
				.sqsAsyncClientSupplier(BaseSqsIntegrationTest::createAsyncClient)
				.acknowledgementResultCallback(new AcknowledgementResultCallback<Object>() {
					@Override
					public void onSuccess(Collection<Message<Object>> messages) {
						if (RECEIVES_MESSAGE_BATCH_QUEUE_NAME.equals(MessageHeaderUtils.getHeaderAsString(messages.iterator().next(),
							SqsHeaders.SQS_QUEUE_NAME_HEADER))) {
							latchContainer.acknowledgementCallbackBatchLatch.countDown();
						}
					}
				})
				.build();
		}

		@Bean
		public MessageListenerContainer<Object> manuallyCreatedContainer() throws Exception {
			SqsAsyncClient client = BaseSqsIntegrationTest.createAsyncClient();
			String queueUrl = client.getQueueUrl(req -> req.queueName(MANUALLY_CREATE_CONTAINER_QUEUE_NAME)).get()
					.queueUrl();
			return SqsMessageListenerContainer
				.builder()
				.queueNames(queueUrl)
				.sqsAsyncClient(client)
				.configure(options -> options
					.maxDelayBetweenPolls(Duration.ofSeconds(1))
					.pollTimeout(Duration.ofSeconds(3)))
				.messageListener(msg -> latchContainer.manuallyCreatedContainerLatch.countDown())
				.build();
		}

		@Bean("inactiveContainer")
		public MessageListenerContainer<Object> manuallyCreatedInactiveContainer() throws Exception {
			SqsAsyncClient client = BaseSqsIntegrationTest.createAsyncClient();
			String queueUrl = client.getQueueUrl(req -> req.queueName(MANUALLY_CREATE_INACTIVE_CONTAINER_QUEUE_NAME)).get()
				.queueUrl();
			return SqsMessageListenerContainer
				.builder()
				.queueNames(queueUrl)
				.sqsAsyncClient(client)
				.configure(options -> options
					.autoStartup(false)
					.maxDelayBetweenPolls(Duration.ofSeconds(1))
					.pollTimeout(Duration.ofSeconds(3)))
				.messageListener(msg -> {latchContainer.manuallyInactiveCreatedContainerLatch.countDown();})
				.build();
		}

		@Bean
		public SqsMessageListenerContainer<String> manuallyCreatedFactory() {
			SqsMessageListenerContainerFactory<String> factory = new SqsMessageListenerContainerFactory<>();
			factory.configure(options -> options
				.maxConcurrentMessages(1)
				.pollTimeout(Duration.ofSeconds(3))
				.maxMessagesPerPoll(1)
				.maxDelayBetweenPolls(Duration.ofSeconds(1)));
			factory.setContainerComponentFactories(Collections.singletonList(new StandardSqsComponentFactory<String>() {
				@Override
				public MessageSource<String> createMessageSource(SqsContainerOptions options) {
					latchContainer.manuallyCreatedFactorySourceFactoryLatch.countDown();
					return super.createMessageSource(options);
				}

				@Override
				public MessageSink<String> createMessageSink(SqsContainerOptions options) {
					latchContainer.manuallyCreatedFactorySinkLatch.countDown();
					return super.createMessageSink(options);
				}
			}));
			factory.setSqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient());
			factory.setMessageListener(msg -> latchContainer.manuallyCreatedFactoryLatch.countDown());
			return factory.createContainer(MANUALLY_CREATE_FACTORY_QUEUE_NAME);
		}
		// @formatter:on

		LatchContainer latchContainer = new LatchContainer();

		@Bean
		ReceivesMessageListener receivesMessageListener() {
			return new ReceivesMessageListener();
		}

		@Bean
		ReceivesMessageBatchListener receivesBatchMessageListener() {
			return new ReceivesMessageBatchListener();
		}

		@Bean
		ReceivesMessageAsyncListener receivesMessageAsyncListener() {
			return new ReceivesMessageAsyncListener();
		}

		@Bean
		DoesNotAckOnErrorListener doesNotAckOnErrorListener() {
			return new DoesNotAckOnErrorListener();
		}

		@Bean
		DoesNotAckOnErrorAsyncListener doesNotAckOnErrorAsyncListener() {
			return new DoesNotAckOnErrorAsyncListener();
		}

		@Bean
		DoesNotAckOnErrorBatchListener doesNotAckOnErrorBatchListener() {
			return new DoesNotAckOnErrorBatchListener();
		}

		@Bean
		DoesNotAckOnErrorAsyncBatchListener doesNotAckOnErrorAsyncBatchListener() {
			return new DoesNotAckOnErrorAsyncBatchListener();
		}

		@Bean
		ResolvesParameterTypesListener resolvesParameterTypesListener() {
			return new ResolvesParameterTypesListener();
		}

		@Bean
		MaxConcurrentMessagesListener maxConcurrentMessagesListener() {
			return new MaxConcurrentMessagesListener();
		}

		@Bean
		SqsListenerConfigurer customizer() {
			return registrar -> {
				registrar.setMessageHandlerMethodFactory(new DefaultMessageHandlerMethodFactory() {
					@Override
					public InvocableHandlerMethod createInvocableHandlerMethod(Object bean, Method method) {
						latchContainer.invocableHandlerMethodLatch.countDown();
						return super.createInvocableHandlerMethod(bean, method);
					}
				});
			};
		}

		@Bean
		LatchContainer latchContainer() {
			return this.latchContainer;
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		SqsTemplate sqsTemplate() {
			return SqsTemplate.builder().sqsAsyncClient(BaseSqsIntegrationTest.createAsyncClient()).build();
		}

		private AsyncMessageInterceptor<Object> testInterceptor() {
			return new AsyncMessageInterceptor<Object>() {
				@Override
				public CompletableFuture<Message<Object>> intercept(Message<Object> message) {
					latchContainer.interceptorLatch.countDown();
					return CompletableFuture.completedFuture(message);
				}
			};
		}

		private AsyncErrorHandler<Object> testErrorHandler() {
			return new AsyncErrorHandler<Object>() {

				final List<Object> previousMessages = Collections.synchronizedList(new ArrayList<>());

				@Override
				public CompletableFuture<Void> handle(Message<Object> message, Throwable t) {
					// Eventually ack to not interfere with other tests.
					if (previousMessages.contains(message.getPayload())) {
						return CompletableFuture.completedFuture(null);
					}
					previousMessages.add(message.getPayload());
					return CompletableFutures.failedFuture(t);
				}

				@Override
				public CompletableFuture<Void> handle(Collection<Message<Object>> messages, Throwable t) {
					// Eventually ack to not interfere with other tests.
					if (previousMessages.containsAll(toPayloadList(messages))) {
						return CompletableFuture.completedFuture(null);
					}
					previousMessages.addAll(toPayloadList(messages));
					return CompletableFutures.failedFuture(t);
				}

				private List<Object> toPayloadList(Collection<Message<Object>> messages) {
					return messages.stream().map(Message::getPayload).collect(Collectors.toList());
				}

				private Collection<DeleteMessageBatchRequestEntry> getBatchEntries(
						Collection<Message<Object>> messages) {
					return messages.stream().map(this::getBatchEntry).collect(Collectors.toList());
				}

				private DeleteMessageBatchRequestEntry getBatchEntry(Message<Object> message) {
					return DeleteMessageBatchRequestEntry.builder().id(UUID.randomUUID().toString())
							.receiptHandle(
									MessageHeaderUtils.getHeaderAsString(message, SqsHeaders.SQS_RECEIPT_HANDLE_HEADER))
							.build();
				}
			};
		}

		private AcknowledgementResultCallback<Object> getAcknowledgementResultCallback() {
			return new AcknowledgementResultCallback<>() {
				@Override
				public void onSuccess(Collection<Message<Object>> messages) {
					logger.debug("Invoking on success acknowledgement result callback for {}",
							MessageHeaderUtils.getId(messages));
					if (RECEIVES_MESSAGE_QUEUE_NAME.equals(MessageHeaderUtils
							.getHeaderAsString(messages.iterator().next(), SqsHeaders.SQS_QUEUE_NAME_HEADER))) {
						latchContainer.acknowledgementCallbackSuccessLatch.countDown();
					}
				}

				@Override
				public void onFailure(Collection<Message<Object>> messages, Throwable t) {
					logger.debug("Invoking on failure acknowledgement result callback for {}",
							MessageHeaderUtils.getId(messages));
					if (DOES_NOT_ACK_ON_ERROR_QUEUE_NAME.equals(MessageHeaderUtils
							.getHeaderAsString(messages.iterator().next(), SqsHeaders.SQS_QUEUE_NAME_HEADER))) {
						latchContainer.acknowledgementCallbackErrorLatch.countDown();
					}
				}
			};
		}

	}

}
