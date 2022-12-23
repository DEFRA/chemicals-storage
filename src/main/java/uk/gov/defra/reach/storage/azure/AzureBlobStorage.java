package uk.gov.defra.reach.storage.azure;

import static java.time.ZoneOffset.UTC;
import static java.util.Date.from;

import com.google.common.io.ByteStreams;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.defra.reach.storage.Storage;
import uk.gov.defra.reach.storage.StorageFilename;


/**
 * Concrete implementation of {@link Storage} which talks to Azure Blob Storage.
 */
public class AzureBlobStorage implements Storage {

  private static final Logger LOGGER = LoggerFactory.getLogger(AzureBlobStorage.class);

  private final CloudBlobContainer container;

  /**
   * Lifetime of a generated SAS token.
   */
  private final Duration expiry;

  private final AzureBlobStorageHealthCheck health;

  public AzureBlobStorage(CloudBlobContainer container, AzureBlobStorageConfiguration configuration) {
    this.container = container;
    this.expiry = configuration.getSasTokenTTL();
    this.health = new AzureBlobStorageHealthCheck(container, configuration);
  }

  @Override
  public String store(InputStream file, StorageFilename filename) throws IOException {
    try {
      CloudBlockBlob blockBlob = container.getBlockBlobReference(filename.get());
      try (OutputStream azure = blockBlob.openOutputStream()) {
        // Microsoft code will read accumulate 4MB of data in memory, before transmitting
        ByteStreams.copy(file, azure);
      }
      return blockBlob.getProperties().getContentMD5();
    } catch (Exception e) {
      throw new IOException("Unable to persist file using Azure Blob Storage API!", e);
    }
  }

  @Override
  public boolean delete(StorageFilename filename) throws IOException {
    try {
      CloudBlockBlob blockBlob = container.getBlockBlobReference(filename.get());
      return blockBlob.deleteIfExists();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Filename cannot be dereferenced by Azure Blob Storage API!", e);
    } catch (Exception e) {
      throw new IOException("Unable to delete file using Azure Blob Storage API!", e);
    }
  }

  @Override
  public URI get(StorageFilename filename) throws IOException {
    try {
      CloudBlockBlob blockBlob = getCloudBlockBlobIfExists(filename);
      SharedAccessBlobPolicy policy = getSharedAccessBlobPolicy();
      return URI.create(blockBlob.getUri().toString() + "?" + blockBlob.generateSharedAccessSignature(policy, null));
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Filename cannot be dereferenced by Azure Blob Storage API!", e);
    } catch (StorageException | InvalidKeyException e) {
      throw new IOException("Unable to retrieve file using Azure Blob Storage API!", e);
    }
  }

  @Override
  public boolean exists(StorageFilename storageFilename) throws IOException {
    try {
      CloudBlockBlob blockBlob = container.getBlockBlobReference(storageFilename.get());
      return blockBlob.exists();
    } catch (URISyntaxException | StorageException e) {
      throw new IOException("Error checking if file exists", e);
    }
  }

  @Override
  public Supplier<Boolean> getHealthCheck() {
    return health::getAsBoolean;
  }

  private CloudBlockBlob getCloudBlockBlobIfExists(StorageFilename filename)
      throws StorageException, URISyntaxException, FileNotFoundException {
    CloudBlockBlob blockBlob = container.getBlockBlobReference(filename.get());
    if (!blockBlob.exists()) {
      throw new FileNotFoundException("Cannot find file \"" + filename + "\"!");
    }
    return blockBlob;
  }

  private SharedAccessBlobPolicy getSharedAccessBlobPolicy() {
    SharedAccessBlobPolicy blobPolicy = new SharedAccessBlobPolicy();
    blobPolicy.setSharedAccessExpiryTime(from(getSasTokenExpiryDate().toInstant()));
    blobPolicy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ));
    return blobPolicy;
  }

  private OffsetDateTime getSasTokenExpiryDate() {
    return OffsetDateTime.now(UTC).plus(expiry);
  }

  /**
   * <p>
   * Background polls an Azure Storage Blob Storage for its health;<br/> reveals most recently determined health via a non-blocking in-memory operation.
   * </p>
   */
  @ThreadSafe
  @SuppressWarnings("Duplicates") // will be fixed once AAA project is remodularized
  private static class AzureBlobStorageHealthCheck implements BooleanSupplier {

    private static final String ERROR_MESSAGE_TEMPLATE = "Azure Blob Storage Container \"%s\" does not exist, or is inaccessible!";

    private static final long BEGIN_IMMEDIATELY = 0;

    private static final long POLLING_FREQUENCY = 60; // seconds

    private final String containerName;

    private final ScheduledExecutorService executor = Executors
        .newSingleThreadScheduledExecutor(HealthCheckThreadFactory.INSTANCE);

    private final Lock lock = new ReentrantLock();

    private final Condition determined = lock.newCondition();

    @GuardedBy("lock")
    private Boolean healthy = null;

    /**
     * Don't pollute logs by repeatedly describing the same failure.
     */
    @GuardedBy("lock")
    private boolean logged = false;

    private final CloudBlobContainer container;

    AzureBlobStorageHealthCheck(CloudBlobContainer container,
        AzureBlobStorageConfiguration configuration) {
      this.container = container;
      this.containerName = configuration.getContainerName();
      beginPolling();
    }

    @Override
    public boolean getAsBoolean() {
      lock.lock();
      try {
        while (healthy == null) {
          determined.awaitUninterruptibly();
        }
        return healthy;
      } finally {
        lock.unlock();
      }
    }

    private void beginPolling() {
      executor.scheduleWithFixedDelay(new HealthCheckTask(), BEGIN_IMMEDIATELY, POLLING_FREQUENCY, TimeUnit.SECONDS);
    }

    private class HealthCheckTask implements Runnable {

      @Override
      public void run() {

        boolean result;
        StorageException exception = null;
        try {
          // perform blocking operation without holding lock
          result = container.exists();
        } catch (StorageException e) {
          exception = e;
          result = false;
        }

        lock.lock();
        try {
          copyToSharedMemory(result);
          logFailure(result, exception);
          determined.signalAll();
        } finally {
          lock.unlock();
        }

      }

      @GuardedBy("lock")
      private void copyToSharedMemory(boolean result) {
        AzureBlobStorageHealthCheck.this.healthy = result;
        if (!result) {
          // ensure a subsequent failure would be logged
          AzureBlobStorageHealthCheck.this.logged = false;
        }
      }

      @GuardedBy("lock")
      private void logFailure(boolean result, StorageException exception) {
        if (!result && !logged) {
          // a new failure has occurred, log it
          if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(String.format(ERROR_MESSAGE_TEMPLATE, containerName), exception);
          }
          // prevent the same failure being logged next time around
          logged = true;
        }
      }
    }

    private static class HealthCheckThreadFactory implements ThreadFactory {

      private static final AtomicInteger COUNTER = new AtomicInteger(0);

      static final ThreadFactory INSTANCE = new HealthCheckThreadFactory();

      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName(
            AzureBlobStorageHealthCheck.class.getSimpleName() + " polling thread " + COUNTER
                .getAndIncrement());
        thread.setUncaughtExceptionHandler(
            (ignored, throwable) -> LOGGER.error(
                "Uncaught throwable in " + AzureBlobStorageHealthCheck.class.getSimpleName() + "!",
                throwable)
        );
        return thread;
      }

    }

  }

}
