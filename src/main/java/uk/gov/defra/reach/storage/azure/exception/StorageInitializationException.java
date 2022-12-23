package uk.gov.defra.reach.storage.azure.exception;

public class StorageInitializationException extends Exception {

  private static final long serialVersionUID = 2466287313507635181L;

  public StorageInitializationException(Throwable cause) {
    super("Cannot initialize storage!", cause);
  }

  public StorageInitializationException(String message) {
    super(message);
  }
}
