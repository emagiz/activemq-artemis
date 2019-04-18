/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.server.impl;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.ActiveMQIllegalStateException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActivateCallback;
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.server.NodeManager;
import org.apache.activemq.artemis.utils.UUID;
import org.jboss.logging.Logger;

/**
 * @author BasElzinga
 *
 */
public class FileLockNodeManager extends NodeManager {

   private static final Logger logger = Logger.getLogger(FileLockNodeManager.class);

   private static final long STATE_LOCK_POS = 0;

   private static final long LIVE_LOCK_POS = 1;

   private static final long BACKUP_LOCK_POS = 2;

   private static final long LOCK_LENGTH = 1;

   private static final byte LIVE = 'L';

   private static final byte FAILINGBACK = 'F';

   private static final byte PAUSED = 'P';

   private static final byte NOT_STARTED = 'N';
   
   private static final int LOCK_MONITOR_TIMEOUT_MILLIES = 2000;

   private volatile FileLock liveLock;

   private FileLock backupLock;

   protected long lockAcquisitionTimeout = -1;

   protected boolean interrupted = false;

   private ScheduledExecutorService scheduledPool;

   public FileLockNodeManager(final File directory, boolean replicatedBackup) {
      super(replicatedBackup, directory);
      this.scheduledPool = new ScheduledThreadPoolExecutor(1);
   }

   public FileLockNodeManager(final File directory, boolean replicatedBackup, long lockAcquisitionTimeout) {
      super(replicatedBackup, directory);

      this.scheduledPool = new ScheduledThreadPoolExecutor(1);
      this.lockAcquisitionTimeout = lockAcquisitionTimeout;
   }
   
   public FileLockNodeManager(final File directory, boolean replicatedBackup, ScheduledExecutorService scheduledPool) {
      super(replicatedBackup, directory);
      this.scheduledPool = scheduledPool;
   }

   public FileLockNodeManager(final File directory, boolean replicatedBackup, long lockAcquisitionTimeout, ScheduledExecutorService scheduledPool) {
      super(replicatedBackup, directory);

      this.scheduledPool = scheduledPool;
      this.lockAcquisitionTimeout = lockAcquisitionTimeout;
   }

   @Override
   public synchronized void start() throws Exception {
      if (isStarted()) {
         return;
      }
      if (!replicatedBackup) {
         setUpServerLockFile();
      }

      super.start();
   }

   @Override
   public boolean isAwaitingFailback() throws Exception {
      return getState() == FileLockNodeManager.FAILINGBACK;
   }

   @Override
   public boolean isBackupLive() throws Exception {
      FileLock liveAttemptLock;
      liveAttemptLock = tryLock(FileLockNodeManager.LIVE_LOCK_POS);
      if (liveAttemptLock == null) {
         return true;
      } else {
         liveAttemptLock.release();
         return false;
      }
   }

   public boolean isLiveLocked() {
      return liveLock != null;
   }

   @Override
   public void interrupt() {
      interrupted = true;
   }

   @Override
   public final void releaseBackup() throws Exception {
      if (backupLock != null) {
         backupLock.release();
         backupLock = null;
      }
   }

   @Override
   public void awaitLiveNode() throws Exception {
      logger.debug("awaiting live node...");
      do {
         byte state = getState();
         while (state == FileLockNodeManager.NOT_STARTED || state == FIRST_TIME_START) {
            logger.debug("awaiting live node startup state='" + state + "'");
            Thread.sleep(2000);
            state = getState();
         }

         liveLock = lock(FileLockNodeManager.LIVE_LOCK_POS);
         if (interrupted) {
            interrupted = false;
            throw new InterruptedException("Lock was interrupted");
         }
         state = getState();
         if (state == FileLockNodeManager.PAUSED) {
            liveLock.release();
            logger.debug("awaiting live node restarting");
            Thread.sleep(2000);
         } else if (state == FileLockNodeManager.FAILINGBACK) {
            liveLock.release();
            logger.debug("awaiting live node failing back");
            Thread.sleep(2000);
         } else if (state == FileLockNodeManager.LIVE) {
            logger.debug("acquired live node lock state = " + (char) state);
            break;
         }
      }
      while (true);
   }

   @Override
   public void startBackup() throws Exception {
      assert !replicatedBackup; // should not be called if this is a replicating backup
      ActiveMQServerLogger.LOGGER.waitingToBecomeBackup();

      backupLock = lock(FileLockNodeManager.BACKUP_LOCK_POS);
      ActiveMQServerLogger.LOGGER.gotBackupLock();
      if (getUUID() == null)
         readNodeId();
   }

   @Override
   public ActivateCallback startLiveNode() throws Exception {
      setFailingBack();

      String timeoutMessage = lockAcquisitionTimeout == -1 ? "indefinitely" : lockAcquisitionTimeout + " milliseconds";

      ActiveMQServerLogger.LOGGER.waitingToObtainLiveLock(timeoutMessage);

      liveLock = lock(FileLockNodeManager.LIVE_LOCK_POS);

      ActiveMQServerLogger.LOGGER.obtainedLiveLock();

      return new ActivateCallback() {
         @Override
         public void activationComplete() {
            try {
               setLive();
               startLockMonitoring();
            } catch (Exception e) {
               ActiveMQServerLogger.LOGGER.warn(e.getMessage(), e);
            }
         }
      };
   }

   @Override
   public void pauseLiveServer() throws Exception {
	  stopLockMonitoring();
      setPaused();
      if (liveLock != null) {
         liveLock.release();
      }
   }

   @Override
   public void crashLiveServer() throws Exception {
	  stopLockMonitoring();
      if (liveLock != null) {
         liveLock.release();
         liveLock = null;
      }
   }

   @Override
   public void awaitLiveStatus() throws Exception {
      while (getState() != LIVE) {
         Thread.sleep(2000);
      }
   }

   private void setLive() throws Exception {
      writeFileLockStatus(FileLockNodeManager.LIVE);
   }

   private void setFailingBack() throws Exception {
      writeFileLockStatus(FAILINGBACK);
   }

   private void setPaused() throws Exception {
      writeFileLockStatus(PAUSED);
   }

   /**
    * @param status
    * @throws IOException
    */
   private void writeFileLockStatus(byte status) throws Exception {
      if (replicatedBackup && channel == null)
         return;
      logger.debug("writing status: " + status);
      ByteBuffer bb = ByteBuffer.allocateDirect(1);
      bb.put(status);
      bb.position(0);
      if (!channel.isOpen()) {
         setUpServerLockFile();
      }
      FileLock lock = null;
      try {
         lock = lock(STATE_LOCK_POS);
         channel.write(bb, 0);
         channel.force(true);
      } finally {
         if (lock != null) {
            lock.release();
         }
      }
   }

   private byte getState() throws Exception {
      byte result;
      logger.debug("getting state...");
      ByteBuffer bb = ByteBuffer.allocateDirect(1);
      int read;
      FileLock lock = null;
      try {
         lock = lock(STATE_LOCK_POS);
         read = channel.read(bb, 0);
         if (read <= 0) {
            result = FileLockNodeManager.NOT_STARTED;
         } else {
            result = bb.get(0);
         }
      } finally {
         if (lock != null) {
            lock.release();
         }
      }

      logger.debug("state: " + result);

      return result;
   }

   @Override
   public final SimpleString readNodeId() throws ActiveMQIllegalStateException, IOException {
      ByteBuffer id = ByteBuffer.allocateDirect(16);
      int read = channel.read(id, 3);
      if (read != 16) {
         throw new ActiveMQIllegalStateException("live server did not write id to file");
      }
      byte[] bytes = new byte[16];
      id.position(0);
      id.get(bytes);
      setUUID(new UUID(UUID.TYPE_TIME_BASED, bytes));
      return getNodeId();
   }

   protected FileLock tryLock(final long lockPos) throws IOException {
      try {
         logger.debug("trying to lock position: " + lockPos);
         FileLock lock = channel.tryLock(lockPos, LOCK_LENGTH, false);
         if (lock != null) {
            logger.debug("locked position: " + lockPos);
         } else {
            logger.debug("failed to lock position: " + lockPos);
         }
         return lock;
      } catch (java.nio.channels.OverlappingFileLockException ex) {
         // This just means that another object on the same JVM is holding the lock
         return null;
      }
   }

   protected FileLock lock(final long lockPosition) throws Exception {
      long start = System.currentTimeMillis();

      while (!interrupted) {
         FileLock lock = tryLock(lockPosition);

         if (lock == null) {
            try {
               Thread.sleep(500);
            } catch (InterruptedException e) {
               return null;
            }

            if (lockAcquisitionTimeout != -1 && (System.currentTimeMillis() - start) > lockAcquisitionTimeout) {
               throw new Exception("timed out waiting for lock");
            }
         } else {
            return lock;
         }
      }

      // todo this is here because sometimes channel.lock throws a resource deadlock exception but trylock works,
      // need to investigate further and review
      FileLock lock;
      do {
         lock = tryLock(lockPosition);
         if (lock == null) {
            try {
               Thread.sleep(500);
            } catch (InterruptedException e1) {
               //
            }
         }
         if (interrupted) {
            interrupted = false;
            throw new IOException("Lock was interrupted");
         }
      }
      while (lock == null);
      return lock;
   }
   
   private synchronized void startLockMonitoring()	{
	   logger.debug("Starting the lock monitor");
	   if (scheduledLockMonitor == null)	{
		   MonitorLock monitorLock = new MonitorLock();
		   scheduledLockMonitor = scheduledPool.scheduleAtFixedRate(monitorLock, LOCK_MONITOR_TIMEOUT_MILLIES, LOCK_MONITOR_TIMEOUT_MILLIES, TimeUnit.MILLISECONDS);
	   } else {
		   logger.debug("Lock monitor was already started");
	   }
   }
   
   private synchronized void stopLockMonitoring()	{
	   logger.debug("Stopping the lock monitor");
	   if (scheduledLockMonitor != null)	{
		   scheduledLockMonitor.cancel(true);
		   scheduledLockMonitor = null;
	   } else {
		   logger.debug("The lock monitor was already stopped");
	   }
   }
   
	private void notifyLostLock() {
		// Additional check we are not initializing or have no locking object anymore because of a shutdown
		if (lockListeners != null && liveLock != null) { 
			Set<LockListener> lockListenersSnapshot = null;

			// Snapshot of the set because I'm not sure if we can trigger concurrent
			// modification exception here if we don't
			synchronized (lockListeners) {
				lockListenersSnapshot = new HashSet<>(lockListeners);
			}

			lockListenersSnapshot.forEach(lockListener -> {
				try {
					lockListener.lostLock();
				} catch (Exception e) {
					// Need to notify everyone so ignore any exception
				}
			});
		}
	}

	public void registerLockListener(LockListener lockListener) {
		lockListeners.add(lockListener);
	}

	public void unregisterLockListener(LockListener lockListener) {
		lockListeners.remove(lockListener);
	}

   protected final Set<LockListener> lockListeners = Collections.synchronizedSet(new HashSet<LockListener>());

   private ScheduledFuture<?> scheduledLockMonitor;
   
   public abstract class LockListener	{
	   protected abstract void lostLock() throws Exception;
	   
	   protected void unregisterListener()	{
		   lockListeners.remove(this);
	   }
   }
   
   public class MonitorLock	implements Runnable {
	   
	@Override
	public void run() {
		
		boolean lostLock = true;
		try {
			if (liveLock == null)	{
				logger.debug("Livelock is null");
			}
			lostLock = (liveLock != null && !liveLock.isValid()) || liveLock == null;
			if (!lostLock)	{
				logger.debug("Server still has the lock, double check status is live");
		// Java always thinks the lock is still valid even when there is no filesystem so we do another check
				
		// Should be able to retrieve the status unless something is wrong
		// When EFS is gone, this locks. Which can be solved but is a lot of threading work where we need to
		// manage the timeout ourselves and interrupt the thread used to claim the lock.
				byte state = getState();
				if (state == LIVE)	{
					logger.debug("Status is set to live"); 
				} else {
					logger.debug("Status is not live");
				}
			}
		} catch (Exception exception) {
			// If something went wrong we probably lost the lock
			logger.error(exception.getMessage(), exception);
			lostLock = true;
		}
		
		if (lostLock)	{
			logger.warn("Lost the lock according to the monitor, notifying listeners");
			notifyLostLock();
		}

	}
	   
   }

}
