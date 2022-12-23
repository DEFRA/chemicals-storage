package uk.gov.defra.reach.storage.azure;

import java.time.Duration;
import javax.annotation.concurrent.Immutable;

@Immutable
public final class AzureBlobStorageConfiguration {

  private final String connectionString;
  private final String containerName;
  private final Duration sasTokenTTL;

  public AzureBlobStorageConfiguration(String connectionString, String containerName, Duration sasTokenTTL) {
    this.connectionString = connectionString;
    this.containerName = containerName;
    this.sasTokenTTL = sasTokenTTL;
  }

  /**
   * <p>
   * Describes the:
   * <ul>
   *   <li>protocol,</li>
   *   <li>credentials, and</li>
   *   <li>endpoint</li>
   * </ul>
   * used to communicate with Azure Blob Storage.
   * </p>
   */
  public String getConnectionString() {
    return connectionString;
  }

  /**
   * <p>
   * The name of the Storage Container being hosted by Azure,
   * under the account described by the {@link #getConnectionString() connection string}.
   * </p>
   */
  public String getContainerName() {
    return containerName;
  }

  /**
   * @return the lifetime of a SAS token, after generation
   */
  public Duration getSasTokenTTL() {
    return sasTokenTTL;
  }

}
