package uk.gov.defra.reach.storage.azure;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import uk.gov.defra.reach.storage.azure.exception.StorageInitializationException;


public class CloudBlobContainerConnection {

  private CloudBlobContainer container;

  public CloudBlobContainerConnection(AzureBlobStorageConfiguration config) throws StorageInitializationException {
    try {
      CloudStorageAccount storageAccount = CloudStorageAccount.parse(config.getConnectionString());
      CloudBlobClient blobClient = storageAccount.createCloudBlobClient();
      container = blobClient.getContainerReference(config.getContainerName());
      if (!container.exists()) {
        throw new StorageInitializationException("Storage container [" + config.getContainerName() + "] does not exist!");
      }
    } catch (URISyntaxException | InvalidKeyException | StorageException e) {
      throw new StorageInitializationException(e);
    }
  }

  public CloudBlobContainer getContainer() {
    return container;
  }
}
