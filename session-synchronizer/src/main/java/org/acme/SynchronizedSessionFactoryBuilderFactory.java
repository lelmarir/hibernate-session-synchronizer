package org.acme;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hibernate.BaseSessionEventListener;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.spi.AbstractDelegatingSessionFactoryBuilderImplementor;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.boot.spi.SessionFactoryBuilderFactory;
import org.hibernate.boot.spi.SessionFactoryBuilderImplementor;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.bytecode.internal.SessionFactoryObserverForBytecodeEnhancer;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.engine.spi.SessionBuilderImplementor;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.SessionCreationOptions;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.internal.SessionFactoryImpl.SessionBuilderImpl;
import org.hibernate.internal.SessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;

public class SynchronizedSessionFactoryBuilderFactory implements SessionFactoryBuilderFactory {

	private static final long SOFT_LOCK_TIMEOUT_SECONDS = 10;
	private static final long MAX_LOCK_TIMEOUT_SECONDS = 60;

	private static class SynchronizedSessionFactoryBuilder
			extends AbstractDelegatingSessionFactoryBuilderImplementor<SessionFactoryBuilderImplementor> {

		private MetadataImplementor metadata;

		public SynchronizedSessionFactoryBuilder(MetadataImplementor metadata,
				SessionFactoryBuilderImplementor delegate) {
			super(delegate);
			this.metadata = metadata;
		}

		@Override
		protected SessionFactoryBuilderImplementor getThis() {
			return this;
		}

		@Override
		public SessionFactory build() {
			final StandardServiceRegistry serviceRegistry = metadata.getMetadataBuildingOptions().getServiceRegistry();
			BytecodeProvider bytecodeProvider = serviceRegistry.getService(BytecodeProvider.class);
			addSessionFactoryObservers(new SessionFactoryObserverForBytecodeEnhancer(bytecodeProvider));
			return new SynchronizedSessionFactoryImpl(metadata, buildSessionFactoryOptions());
		}

	}

	private static class SynchronizedSessionFactoryImpl extends SessionFactoryImpl {

		public SynchronizedSessionFactoryImpl(final MetadataImplementor metadata, SessionFactoryOptions options) {
			super(metadata, options);
		}

		@Override
		public SessionBuilderImplementor withOptions() {
			return new SynchronizedSessionBuilderImpl(this);
		}
	}

	private static class SynchronizedSessionBuilderImpl extends SessionBuilderImpl {

		private static final org.jboss.logging.Logger log = CoreLogging.logger(SessionBuilderImpl.class);
		private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizedSessionBuilderImpl.class);

		private final SessionFactoryImpl sessionFactory;

		private final Cache<Thread, Boolean> unsincronizedThreadsCache = CacheBuilder.newBuilder()
				.expireAfterWrite(MAX_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS).build();

		public SynchronizedSessionBuilderImpl(SessionFactoryImpl sessionFactory) {
			super(sessionFactory);
			this.sessionFactory = sessionFactory;
		}

		@Override
		public Session openSession() {
			log.tracef("Opening Hibernate Session.  tenant=%s", getTenantIdentifier());

			AtomicReference<Thread> ownerThreadRef = new AtomicReference<>(Thread.currentThread());
			final ReentrantLock sessionLock = new ReentrantLock();
			boolean aquired = sessionLock.tryLock();
			assert aquired == true;

			Enhancer enhancer = new Enhancer();
			enhancer.setSuperclass(SessionImpl.class);
			enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> {
				final Thread currentThread = Thread.currentThread();
				final Thread ownerThread = ownerThreadRef.get();
				if (ownerThread != null && currentThread != ownerThread
						&& unsincronizedThreadsCache.getIfPresent(currentThread) == null) {
					boolean lockAquired = sessionLock.tryLock();
					if (!lockAquired) {
						LOGGER.warn(
								"The thread {} is accessing the, stil active, EntityManager of another thread ({}) "
										+ ": will wait up to {} seconds: {}",
								currentThread, ownerThread, MAX_LOCK_TIMEOUT_SECONDS, new RuntimeException());

						long lockStartMillis = System.currentTimeMillis();
						ThreadInfo[] deadlockThreads = null;
						while (System.currentTimeMillis() - lockStartMillis < MAX_LOCK_TIMEOUT_SECONDS * 1000) {
							lockAquired = sessionLock.tryLock(SOFT_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
							if (lockAquired) {
								break;
							}

							deadlockThreads = detectDeadlockThreads();
							if (deadlockThreads != null && deadlockThreads.length > 0) {
								if (Stream.of(deadlockThreads).anyMatch(t -> t.getThreadId() == ownerThread.getId()
										|| Objects.equals(t.getThreadId(), currentThread.getId()))) {
									LOGGER.error("Deadlock detected between threads {} (current={}, owner={})",
											Stream.of(deadlockThreads).map(ThreadInfo::getThreadName)
													.collect(Collectors.toList()),
											currentThread, ownerThread);
									break;
								} else {
									LOGGER.error(
											"Deadlock detected between threads {}, but not on interseted ones (current={}, owner={})",
											Stream.of(deadlockThreads).map(ThreadInfo::getThreadName)
													.collect(Collectors.toList()),
											currentThread, ownerThread);
									deadlockThreads = new ThreadInfo[0];
								}
							}
						}
						if (!lockAquired) {
							if (deadlockThreads != null && deadlockThreads.length > 0) {
								// ho un deadlock in corso
								LOGGER.error(
										"A deadlock has been detected while the thread {} was waiting for the EntityManager of ({}) (after {}ms)"
												+ " : will invoke anyway, this may causa a concurrent acces exception\nentityOwnerThread current stack: \n{}",
										currentThread, ownerThread, System.currentTimeMillis() - lockStartMillis,
										Arrays.toString(ownerThread.getStackTrace()), new RuntimeException());
							} else {
								// nessun deadlock rilevato, ma ho superato MAX_LOCK_TIMEOUT_SECONDS
								assert System.currentTimeMillis() - lockStartMillis >= MAX_LOCK_TIMEOUT_SECONDS * 1000;
								LOGGER.error("The thread {} has waited for the EntityManager of thread ({}) "
										+ " for more than {} seconds (no deadlock detected): will invoke anyway, this may causa a concurrent acces exception\nentityOwnerThread current stack: \n{}",
										currentThread, ownerThread, MAX_LOCK_TIMEOUT_SECONDS,
										Arrays.toString(ownerThread.getStackTrace()), new RuntimeException());
							}
							// this thread will be ignored for MAX_LOCK_TIMEOUT_SECONDS
							unsincronizedThreadsCache.put(currentThread, false);
						}
					}
					try {
						return proxy.invokeSuper(obj, args);
					} finally {
						if (lockAquired) {
							sessionLock.unlock();
						}
					}
				} else {
					return proxy.invokeSuper(obj, args);
				}
			});
			Session session = (Session) enhancer.create(
					new Class<?>[] { SessionFactoryImpl.class, SessionCreationOptions.class },
					new Object[] { sessionFactory, this });
			session.addEventListeners(new BaseSessionEventListener() {
				@Override
				public void end() {
					sessionLock.unlock();
					ownerThreadRef.set(null);
				}
			});
			return session;
		}

		private static ThreadInfo[] detectDeadlockThreads() {
			ThreadMXBean bean = ManagementFactory.getThreadMXBean();
			long[] threadIds = bean.findDeadlockedThreads(); // Returns null if no threads are deadlocked.

			if (threadIds != null) {
				return bean.getThreadInfo(threadIds);
			} else {
				return new ThreadInfo[0];
			}
		}
	}

	@Override
	public SessionFactoryBuilder getSessionFactoryBuilder(MetadataImplementor metadata,
			SessionFactoryBuilderImplementor defaultBuilder) {
		return new SynchronizedSessionFactoryBuilder(metadata, defaultBuilder);
	}

}
