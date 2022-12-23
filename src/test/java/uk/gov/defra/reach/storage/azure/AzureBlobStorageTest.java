package uk.gov.defra.reach.storage.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

import uk.gov.defra.reach.storage.StorageFilename;

@ExtendWith(MockitoExtension.class)
class AzureBlobStorageTest {

  private static final InputStream DATA = new ByteArrayInputStream("data".getBytes());

  private static final Duration SAS_TOKEN_TTL = Duration.ofSeconds(60); // arbitrary

  private static final AzureBlobStorageConfiguration CONFIG = new AzureBlobStorageConfiguration(null, "unit-test", SAS_TOKEN_TTL);

  private static StorageFilename filename;

  private AzureBlobStorage storage;

  @Mock
  private CloudBlobContainer container;

  @Mock
  private CloudBlockBlob blob;

  @BeforeEach
  void before() throws Exception {
    given(container.exists()).willReturn(true);

    storage = new AzureBlobStorage(container, CONFIG);

    filename = StorageFilename.from("valid");

    // blocks until healthcheck thread hits container.exists() at least once
    assertThat(storage.getHealthCheck().get()).isTrue();

  }

  @Test
  void storeShouldPropagateAzureFailure() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(StorageException.class);
    assertThatThrownBy(() -> storage.store(DATA, filename))
        .isInstanceOf(IOException.class);
  }

  @Test
  void deleteShouldPropagateAzureFailure() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(StorageException.class);
    assertThatThrownBy(() -> storage.delete(filename))
        .isInstanceOf(IOException.class);
  }

  @Test
  void getShouldPropagateAzureFailure() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(StorageException.class);
    assertThatThrownBy(() -> storage.get(filename))
        .isInstanceOf(IOException.class);
  }

  @Test
  void shouldThrowFailedTokenGenerationExceptionWhenBlockBlobThrowsInvalidKeyException() throws Exception {
    given(blob.getUri()).willReturn(mock(URI.class));
    given(blob.exists()).willReturn(true);
    given(blob.generateSharedAccessSignature(ArgumentMatchers.any(), ArgumentMatchers.any())).willThrow(InvalidKeyException.class);
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    assertThatThrownBy(() -> storage.get(filename))
        .isInstanceOf(IOException.class);
  }

  @Test
  void shouldThrowFileNotFoundException() throws Exception {
    given(blob.exists()).willReturn(false);
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    assertThatThrownBy(() -> storage.get(filename))
        .isInstanceOf(FileNotFoundException.class);
  }

  @Test
  void existsReturnsTrueIfFileExistsInContainer() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willReturn(true);
    assertThat(storage.exists(filename)).isTrue();
  }

  @Test
  void existsReturnsFalseIfFileDoesNotExistsInContainer() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willReturn(false);
    assertThat(storage.exists(filename)).isFalse();
  }

  @Test
  void existsShouldPropagateAzureFailure() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willThrow(StorageException.class);
    assertThatThrownBy(() -> storage.exists(filename)).isInstanceOf(IOException.class);
  }
  
  @Test
  void deleteThrowsIllegalArgumentForURISyntaxException() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(URISyntaxException.class);
    assertThatThrownBy(() -> storage.delete(filename)).isInstanceOf(IllegalArgumentException.class);
  }
  
  @Test
  void shouldDeleteSuccessfully() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.deleteIfExists()).willReturn(true);
    assertThat(storage.delete(filename)).isTrue();
  }
  
  @Test
  void getThrowsIllegalArgumentForURISyntaxException() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(URISyntaxException.class);
    assertThatThrownBy(() -> storage.get(filename)).isInstanceOf(IllegalArgumentException.class);
  }
  
  @Test
  void getThrowsIOExceptionForInvalidKeyException() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willReturn(true);
    given(blob.getUri()).willReturn(new URI("uri"));
    given(blob.generateSharedAccessSignature(ArgumentMatchers.any(), ArgumentMatchers.any())).willThrow(InvalidKeyException.class);
    assertThatThrownBy(() -> storage.get(filename)).isInstanceOf(IOException.class);
  }
  
  @Test
  void getThrowsIOExceptionForStorageException() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willThrow(StorageException.class);
    assertThatThrownBy(() -> storage.get(filename)).isInstanceOf(IOException.class);
  }
  
  @Test
  void exceptionWhenCallingGetAndFilenameDoesNotExist() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willReturn(false);
    assertThatThrownBy(() -> storage.get(filename)).isInstanceOf(IOException.class);
  }

  @Test
  void shouldGetSuccessfully() throws Exception {
    given(container.getBlockBlobReference(ArgumentMatchers.any())).willReturn(blob);
    given(blob.exists()).willReturn(true);
    given(blob.getUri()).willReturn(new URI("uri"));
    assertThat(storage.get(filename)).isNotNull();
  }
}
