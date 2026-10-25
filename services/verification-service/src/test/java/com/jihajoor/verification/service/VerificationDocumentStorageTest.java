package com.jihajoor.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import com.jihajoor.verification.config.DocumentStorageProperties;
import com.jihajoor.verification.config.VerificationProperties;
import com.jihajoor.verification.domain.DocumentStorageKind;
import com.jihajoor.verification.domain.DocumentType;
import com.jihajoor.verification.domain.Verification;
import com.jihajoor.verification.domain.VerificationDocument;
import com.jihajoor.verification.service.VerificationService.DocumentDownload;
import com.jihajoor.verification.storage.DocumentStorage;
import com.jihajoor.verification.storage.LocalDocumentStorageAdapter;
import com.jihajoor.verification.storage.S3DocumentStorageAdapter;
import com.jihajoor.verification.support.InMemoryVerificationRepository;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Documents are kept and can be read back: the submission writes each file's bytes to the
 * {@link DocumentStorage} and records where they went and the original file name, and
 * {@link VerificationService#openDocument} serves them from that store, whichever one this process
 * writes to. A document from before bytes were kept, one that is not the provider's, and a store
 * that is down each give their own clean error.
 *
 * <p>Real service over the in-memory repository, a real storage on a temporary directory and an S3
 * adapter over a mocked client.
 */
class VerificationDocumentStorageTest {

    @TempDir
    Path dir;

    private InMemoryVerificationRepository repository;
    private LocalDocumentStorageAdapter local;
    private S3Client s3;
    private S3DocumentStorageAdapter s3Adapter;
    private final UUID provider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new InMemoryVerificationRepository();
        local = new LocalDocumentStorageAdapter(dir);
        s3 = mock(S3Client.class);
        DocumentStorageProperties.S3 s3Config = new DocumentStorageProperties.S3();
        s3Config.setBucket("jihajoor-documents");
        s3Config.setRegion("ap-south-1");
        s3Adapter = new S3DocumentStorageAdapter(s3, s3Config);
    }

    private VerificationService serviceWritingTo(DocumentStorage storage) {
        return new VerificationService(repository, storage, provider -> "check", (p, r) -> { }, p -> { },
                new VerificationProperties());
    }

    private static List<DocumentUpload> uploads() {
        return List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[]{1, 2, 3}, "image/jpeg",
                        "C:\\Users\\ravi\\Pictures\\aadhaar.jpg"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[]{4, 5}, "application/pdf", "bill.pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[]{6}, "application/pdf"));
    }

    private static byte[] read(DocumentDownload download) throws Exception {
        try (InputStream in = download.content().stream()) {
            return in.readAllBytes();
        }
    }

    private static VerificationDocument documentOf(Verification v, DocumentType type) {
        return v.getDocuments().stream().filter(d -> d.getDocumentType() == type).findFirst().orElseThrow();
    }

    @Test
    void submittedDocumentsAreStoredOnDiskAndReadBack() throws Exception {
        VerificationService service = serviceWritingTo(new DocumentStorage(local));

        Verification v = service.submitDocuments(provider, uploads(), provider);

        VerificationDocument id = documentOf(v, DocumentType.GOVERNMENT_ID);
        assertThat(id.getStorage()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(id.getStorageRef()).isEqualTo("providers/" + provider + "/GOVERNMENT_ID/" + id.getId() + ".jpg");
        // The client's path is dropped; only the file name is kept.
        assertThat(id.getFileName()).isEqualTo("aadhaar.jpg");
        assertThat(documentOf(v, DocumentType.SKILL_CERTIFICATION).getFileName()).isNull();
        assertThat(Files.readAllBytes(dir.resolve(id.getStorageRef()))).containsExactly(1, 2, 3);

        DocumentDownload download = service.openDocument(provider, id.getId());
        assertThat(download.document().getContentType()).isEqualTo("image/jpeg");
        assertThat(download.content().length()).isEqualTo(3);
        assertThat(read(download)).containsExactly(1, 2, 3);
    }

    @Test
    void withS3TheBytesGoToTheBucketAndLocalDocumentsStayReadable() throws Exception {
        // Submitted while the instance wrote to disk...
        Verification before = serviceWritingTo(new DocumentStorage(local)).submitDocuments(provider, uploads(), provider);
        VerificationDocument onDisk = documentOf(before, DocumentType.ADDRESS_PROOF);

        // ...then S3 is turned on: new documents go to the bucket, the old ones are still served.
        VerificationService withS3 = serviceWritingTo(new DocumentStorage(s3Adapter, local));
        UUID other = UUID.randomUUID();
        Verification after = withS3.submitDocuments(other, uploads(), other);
        VerificationDocument inS3 = documentOf(after, DocumentType.GOVERNMENT_ID);
        assertThat(inS3.getStorage()).isEqualTo(DocumentStorageKind.S3);
        org.mockito.Mockito.verify(s3, org.mockito.Mockito.times(3))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(3L).build(),
                (InputStream) new ByteArrayInputStream(new byte[]{1, 2, 3})));

        assertThat(read(withS3.openDocument(other, inS3.getId()))).containsExactly(1, 2, 3);
        assertThat(read(withS3.openDocument(provider, onDisk.getId()))).containsExactly(4, 5);
    }

    @Test
    void aDocumentFromBeforeBytesWereKeptIsNotStoredAndNoStoreIsAsked() {
        Verification v = Verification.create(provider);
        v.addDocument(DocumentType.GOVERNMENT_ID, "s3://jihajoor-documents/" + provider + "/GOVERNMENT_ID/x",
                "image/jpeg", 10);
        repository.save(v);
        UUID legacyId = v.getDocuments().get(0).getId();
        // A store that would fail if asked: the legacy row must not reach it.
        DocumentStorage storage = mock(DocumentStorage.class);

        assertThatThrownBy(() -> serviceWritingTo(storage).openDocument(provider, legacyId))
                .isInstanceOfSatisfying(VerificationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("DOCUMENT_NOT_STORED");
                });
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test
    void aRecordedDocumentMissingFromItsStoreIsNotStored() throws Exception {
        VerificationService service = serviceWritingTo(new DocumentStorage(local));
        Verification v = service.submitDocuments(provider, uploads(), provider);
        VerificationDocument id = documentOf(v, DocumentType.GOVERNMENT_ID);
        Files.delete(dir.resolve(id.getStorageRef()));

        assertThatThrownBy(() -> service.openDocument(provider, id.getId()))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DOCUMENT_NOT_STORED"));
    }

    @Test
    void anotherProvidersDocumentIsNotFound() {
        VerificationService service = serviceWritingTo(new DocumentStorage(local));
        Verification mine = service.submitDocuments(provider, uploads(), provider);
        UUID someoneElse = UUID.randomUUID();
        service.submitDocuments(someoneElse, uploads(), someoneElse);
        UUID myDocument = mine.getDocuments().get(0).getId();

        assertThatThrownBy(() -> service.openDocument(someoneElse, myDocument))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DOCUMENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.openDocument(UUID.randomUUID(), myDocument))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VERIFICATION_NOT_FOUND"));
    }

    @Test
    void anUploadTheStoreRefusesFailsTheSubmissionWithA503AndRecordsNothing() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        VerificationService service = serviceWritingTo(new DocumentStorage(s3Adapter, local));

        assertThatThrownBy(() -> service.submitDocuments(provider, uploads(), provider))
                .isInstanceOfSatisfying(VerificationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE");
                });
        // The record exists (created PENDING before the uploads; a real transaction would roll it
        // back), but holds no document.
        assertThat(repository.findWithDocumentsByProviderId(provider))
                .hasValueSatisfying(v -> assertThat(v.getDocuments()).isEmpty());
    }

    @Test
    void aReadTheStoreRefusesIsA503() {
        VerificationService service = serviceWritingTo(new DocumentStorage(s3Adapter, local));
        Verification v = service.submitDocuments(provider, uploads(), provider);
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("timed out"));

        assertThatThrownBy(() -> service.openDocument(provider, v.getDocuments().get(0).getId()))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void fileNamesAreReducedToASafeLastSegment() {
        assertThat(VerificationService.sanitizeFileName(null)).isNull();
        assertThat(VerificationService.sanitizeFileName("  ")).isNull();
        assertThat(VerificationService.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(VerificationService.sanitizeFileName("C:\\fakepath\\pan card.png")).isEqualTo("pan card.png");
        assertThat(VerificationService.sanitizeFileName("a\r\nb.pdf")).isEqualTo("ab.pdf");
        assertThat(VerificationService.sanitizeFileName("dir/..")).isNull();
        assertThat(VerificationService.sanitizeFileName("x".repeat(300) + ".pdf"))
                .hasSize(VerificationService.MAX_FILE_NAME_LENGTH);
    }
}
