package com.jihajoor.verification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jihajoor.shared.security.JiHajoorSecurityAutoConfiguration;
import com.jihajoor.verification.config.VerificationRbacConfig;
import com.jihajoor.verification.config.WebSecurityConfig;
import com.jihajoor.verification.domain.DocumentStorageKind;
import com.jihajoor.verification.domain.DocumentType;
import com.jihajoor.verification.domain.Verification;
import com.jihajoor.verification.domain.VerificationDocument;
import com.jihajoor.verification.provider.ProviderDirectoryPort;
import com.jihajoor.verification.service.VerificationException;
import com.jihajoor.verification.service.VerificationService;
import com.jihajoor.verification.service.VerificationService.DocumentDownload;
import com.jihajoor.verification.storage.DocumentContent;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the document-bytes endpoints through the service's real security chain:
 * {@code GET /admin/verification/{providerId}/documents/{documentId}} for the queue admins and
 * {@code GET /verifications/{providerId}/documents/{documentId}} for the provider themselves.
 *
 * <p>Covers the response contract (stored content type for renderable types, octet-stream download
 * for anything else, {@code Cache-Control: private, no-store}, {@code nosniff}, the original file
 * name), the {@code url} the documents list now carries, the clean 404 for a document from before
 * bytes were kept, the 503 when storage is down, and the role and ownership refusals.
 */
@WebMvcTest(controllers = {AdminVerificationQueueController.class, VerificationController.class}, properties = {
        "jihajoor.security.jwt-secret=" + VerificationDocumentContentControllerTest.JWT_SECRET,
        "jihajoor.verification.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, VerificationRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(JiHajoorSecurityAutoConfiguration.class)
class VerificationDocumentContentControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID admin = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationService verificationService;

    @MockitoBean
    private ProviderDirectoryPort providerDirectory;

    private String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private VerificationDocument storedDocument(String fileName, String contentType) {
        UUID id = UUID.randomUUID();
        return Verification.create(provider).addDocument(id, DocumentType.GOVERNMENT_ID, DocumentStorageKind.LOCAL,
                "providers/" + provider + "/GOVERNMENT_ID/" + id + ".bin", fileName, contentType, 3);
    }

    private VerificationDocument serving(String fileName, String contentType, byte[] bytes) {
        VerificationDocument document = storedDocument(fileName, contentType);
        when(verificationService.openDocument(provider, document.getId())).thenReturn(new DocumentDownload(
                document, new DocumentContent(new ByteArrayInputStream(bytes), bytes.length)));
        return document;
    }

    private String adminPath(UUID documentId) {
        return "/admin/verification/" + provider + "/documents/" + documentId;
    }

    // ------------------------------------------------------------------ admin: bytes

    @Test
    void anImageIsServedInlineWithItsTypeAndNeverCached() throws Exception {
        VerificationDocument document = serving("aadhaar front.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(get(adminPath(document.getId())).header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.startsWith("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("aadhaar")))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void aPdfIsServedInlineAsPdf() throws Exception {
        VerificationDocument document = serving("cert.pdf", "application/pdf", new byte[]{'%', 'P', 'D', 'F'});

        mockMvc.perform(get(adminPath(document.getId())).header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.startsWith("inline")))
                .andExpect(content().bytes(new byte[]{'%', 'P', 'D', 'F'}));
    }

    @Test
    void anyOtherDeclaredTypeIsAnOpaqueDownload() throws Exception {
        VerificationDocument document = serving("x.html", "text/html", "<script>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get(adminPath(document.getId())).header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void aDocumentFromBeforeBytesWereKeptIsAClean404() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(verificationService.openDocument(provider, documentId))
                .thenThrow(VerificationException.documentNotStored(documentId));

        mockMvc.perform(get(adminPath(documentId)).header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_STORED"));
    }

    @Test
    void storageDownIsA503() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(verificationService.openDocument(provider, documentId))
                .thenThrow(VerificationException.documentStorageUnavailable());

        mockMvc.perform(get(adminPath(documentId)).header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_STORAGE_UNAVAILABLE"));
    }

    @Test
    void documentBytesAreRefusedToEveryoneButTheQueueAdmins() throws Exception {
        String path = adminPath(UUID.randomUUID());
        for (String role : List.of("SUPPORT_AGENT", "SERVICE_PROVIDER", "DISPATCHER", "CUSTOMER", "FINANCE_ADMIN")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token(admin, role)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());

        verifyNoInteractions(verificationService);
    }

    // ------------------------------------------------------------------ admin: list

    @Test
    void theDocumentsListPointsAtTheBytesEndpointAndNamesTheFile() throws Exception {
        VerificationDocument stored = storedDocument("licence.pdf", "application/pdf");
        Verification legacy = Verification.create(provider);
        legacy.addDocument(DocumentType.ADDRESS_PROOF, "s3://jihajoor-documents/old", "image/png", 5);
        when(verificationService.documentsOf(provider)).thenReturn(List.of(stored, legacy.getDocuments().get(0)));

        mockMvc.perform(get("/admin/verification/" + provider + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value(adminPath(stored.getId())))
                .andExpect(jsonPath("$[0].fileName").value("licence.pdf"))
                // Never stored: nothing to fetch, so no url; the portal says so.
                .andExpect(jsonPath("$[1].url").isEmpty())
                .andExpect(jsonPath("$[1].fileName").isEmpty())
                .andExpect(jsonPath("$[1].storageRef").doesNotExist());
    }

    // ------------------------------------------------------------------ provider: own documents

    @Test
    void aProviderReadsTheirOwnDocument() throws Exception {
        VerificationDocument document = serving("id.png", "image/png", new byte[]{9});

        mockMvc.perform(get("/verifications/" + provider + "/documents/" + document.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().bytes(new byte[]{9}));

        verify(verificationService).openDocument(provider, document.getId());
    }

    @Test
    void aProviderCannotReadAnotherProvidersDocumentNorCanSupport() throws Exception {
        String path = "/verifications/" + provider + "/documents/" + UUID.randomUUID();

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), "SERVICE_PROVIDER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token(admin, "SUPPORT_AGENT")))
                .andExpect(status().isForbidden());

        verify(verificationService, org.mockito.Mockito.never()).openDocument(any(), any());
    }
}
