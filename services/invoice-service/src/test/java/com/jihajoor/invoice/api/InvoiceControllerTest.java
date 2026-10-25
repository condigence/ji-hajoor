package com.jihajoor.invoice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jihajoor.invoice.api.dto.InvoiceSummary;
import com.jihajoor.invoice.api.dto.ProviderEarningsStatement;
import com.jihajoor.invoice.domain.Invoice;
import com.jihajoor.invoice.service.InvoiceDocumentService;
import com.jihajoor.invoice.service.InvoiceException;
import com.jihajoor.invoice.service.InvoiceQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Direct-invocation tests for {@link InvoiceController}. The controller is a thin delegate over
 * {@link InvoiceQueryService}; these tests prove the delegation and parameter forwarding for the
 * customer-history and provider-statement endpoints (Requirements 13.5, 13.6). Standalone MockMvc
 * is not used because the build does not enable the {@code -parameters} compiler flag needed to
 * resolve unnamed {@code @PathVariable}s.
 *
 * <p>Both handlers now also assert ownership through {@link CallerIdentity}, which reads the
 * {@code SecurityContextHolder}; each test therefore authenticates the principal it needs. The two
 * pre-existing delegation tests were updated to authenticate as the customer/provider whose id they
 * pass (rather than gaining a role they do not need), so they still assert delegation and nothing
 * else.
 */
class InvoiceControllerTest {

    private final InvoiceQueryService queryService = mock(InvoiceQueryService.class);
    private final InvoiceDocumentService documentService = mock(InvoiceDocumentService.class);
    private final InvoiceController controller =
            new InvoiceController(queryService, documentService, new CallerIdentity());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID callerId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(callerId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void customerHistory_forwardsPagingAndReturnsSummaries() {
        UUID customerId = UUID.randomUUID();
        authenticate(customerId, "CUSTOMER");
        InvoiceSummary summary = new InvoiceSummary(UUID.randomUUID(), "INV-2024-07-000001",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "https://example/inv.pdf", Instant.now().plusSeconds(3600));
        when(queryService.customerHistory(customerId, 1, 50)).thenReturn(List.of(summary));

        List<InvoiceSummary> result = controller.customerHistory(customerId, 1, 50);

        assertThat(result).containsExactly(summary);
        verify(queryService).customerHistory(customerId, 1, 50);
    }

    @Test
    void providerStatement_forwardsYearMonthAndReturnsStatement() {
        UUID providerId = UUID.randomUUID();
        authenticate(providerId, "SERVICE_PROVIDER");
        ProviderEarningsStatement statement = new ProviderEarningsStatement(providerId, 2024, 6, 2,
                new BigDecimal("826.00"), new BigDecimal("165.20"), new BigDecimal("660.80"), true);
        when(queryService.providerMonthlyStatement(eq(providerId), eq(2024), eq(6)))
                .thenReturn(statement);

        ProviderEarningsStatement result = controller.providerStatement(providerId, 2024, 6);

        assertThat(result).isEqualTo(statement);
        assertThat(result.available()).isTrue();
    }

    // ------------------------------------------------------------------- ownership

    @Test
    void customerHistory_ofAnotherCustomer_isForbidden() {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        authenticate(callerA, "CUSTOMER");

        assertThatThrownBy(() -> controller.customerHistory(callerB, 0, 20))
                .isInstanceOf(InvoiceException.class)
                .satisfies(e -> {
                    InvoiceException ex = (InvoiceException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN");
                });
        verify(queryService, never()).customerHistory(any(), anyInt(), anyInt());
    }

    @Test
    void customerHistory_ofAnyCustomer_isAllowedForStaff() {
        UUID staffId = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        authenticate(staffId, "FINANCE_ADMIN");
        when(queryService.customerHistory(someoneElse, 0, 20)).thenReturn(List.of());

        assertThat(controller.customerHistory(someoneElse, 0, 20)).isEmpty();
        verify(queryService).customerHistory(someoneElse, 0, 20);
    }

    @Test
    void providerStatement_ofAnotherProvider_isForbidden() {
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        authenticate(callerA, "SERVICE_PROVIDER");

        assertThatThrownBy(() -> controller.providerStatement(callerB, 2026, 1))
                .isInstanceOf(InvoiceException.class)
                .satisfies(e -> {
                    InvoiceException ex = (InvoiceException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN");
                });
        verify(queryService, never()).providerMonthlyStatement(any(), anyInt(), anyInt());
    }

    @Test
    void providerStatement_ofOwnProvider_isAllowed() {
        UUID callerA = UUID.randomUUID();
        authenticate(callerA, "SERVICE_PROVIDER");
        ProviderEarningsStatement statement = new ProviderEarningsStatement(callerA, 2026, 1, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
        when(queryService.providerMonthlyStatement(callerA, 2026, 1)).thenReturn(statement);

        assertThat(controller.providerStatement(callerA, 2026, 1)).isEqualTo(statement);
    }

    @Test
    void providerStatement_ofAnyProvider_isAllowedForStaff() {
        UUID staffId = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        authenticate(staffId, "FINANCE_ADMIN");
        ProviderEarningsStatement statement = new ProviderEarningsStatement(someoneElse, 2026, 1, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
        when(queryService.providerMonthlyStatement(someoneElse, 2026, 1)).thenReturn(statement);

        assertThat(controller.providerStatement(someoneElse, 2026, 1)).isEqualTo(statement);
    }

    @Test
    void unauthenticatedCaller_isRejected() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> controller.customerHistory(UUID.randomUUID(), 0, 20))
                .isInstanceOf(InvoiceException.class)
                .satisfies(e -> assertThat(((InvoiceException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ------------------------------------------------- single invoice: by booking, download

    private static Invoice invoice(UUID customerId, UUID providerId) {
        return Invoice.create("INV-2026-10-000042", UUID.randomUUID(), UUID.randomUUID(),
                customerId, providerId, "invoices/" + customerId + "/INV-2026-10-000042.pdf",
                new BigDecimal("590.00"), new BigDecimal("118.00"), new BigDecimal("472.00"),
                Instant.parse("2026-10-01T09:00:00Z"));
    }

    private static InvoiceSummary summaryOf(Invoice invoice) {
        return new InvoiceSummary(invoice.getId(), invoice.getInvoiceNumber(), invoice.getBookingId(),
                invoice.getPaymentId(), invoice.getGeneratedAt(), "https://example/inv.pdf",
                Instant.parse("2026-10-04T09:00:00Z"));
    }

    private static void assertNotFound(Throwable e) {
        InvoiceException ex = (InvoiceException) e;
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("INVOICE_NOT_FOUND");
    }

    @Test
    void download_byInvoiceCustomer_streamsPdfAsNamedAttachment() {
        UUID customerId = UUID.randomUUID();
        Invoice invoice = invoice(customerId, UUID.randomUUID());
        authenticate(customerId, "CUSTOMER");
        byte[] pdf = "%PDF-1.4 bytes".getBytes();
        when(queryService.findInvoice(invoice.getId())).thenReturn(invoice);
        when(documentService.pdf(invoice)).thenReturn(pdf);

        ResponseEntity<byte[]> response = controller.download(invoice.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdf);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(pdf.length);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"INV-2026-10-000042.pdf\"");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void download_byInvoiceProvider_isAllowed() {
        UUID providerId = UUID.randomUUID();
        Invoice invoice = invoice(UUID.randomUUID(), providerId);
        authenticate(providerId, "SERVICE_PROVIDER");
        when(queryService.findInvoice(invoice.getId())).thenReturn(invoice);
        when(documentService.pdf(invoice)).thenReturn(new byte[]{1});

        assertThat(controller.download(invoice.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void download_byFinanceStaff_isAllowedForAnyInvoice() {
        Invoice invoice = invoice(UUID.randomUUID(), UUID.randomUUID());
        authenticate(UUID.randomUUID(), "FINANCE_ADMIN");
        when(queryService.findInvoice(invoice.getId())).thenReturn(invoice);
        when(documentService.pdf(invoice)).thenReturn(new byte[]{1});

        assertThat(controller.download(invoice.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void download_ofAnotherCustomersInvoice_isNotFound_andNeverReadsTheDocument() {
        Invoice invoice = invoice(UUID.randomUUID(), UUID.randomUUID());
        authenticate(UUID.randomUUID(), "CUSTOMER");
        when(queryService.findInvoice(invoice.getId())).thenReturn(invoice);

        assertThatThrownBy(() -> controller.download(invoice.getId()))
                .isInstanceOf(InvoiceException.class)
                .satisfies(InvoiceControllerTest::assertNotFound);
        verify(documentService, never()).pdf(any());
    }

    @Test
    void download_byUnrelatedProvider_isNotFound() {
        Invoice invoice = invoice(UUID.randomUUID(), UUID.randomUUID());
        authenticate(UUID.randomUUID(), "SERVICE_PROVIDER");
        when(queryService.findInvoice(invoice.getId())).thenReturn(invoice);

        assertThatThrownBy(() -> controller.download(invoice.getId()))
                .isInstanceOf(InvoiceException.class)
                .satisfies(InvoiceControllerTest::assertNotFound);
        verify(documentService, never()).pdf(any());
    }

    @Test
    void download_ofUnknownInvoice_isNotFound() {
        UUID unknown = UUID.randomUUID();
        authenticate(UUID.randomUUID(), "CUSTOMER");
        when(queryService.findInvoice(unknown)).thenThrow(InvoiceException.notFound("invoice not found"));

        assertThatThrownBy(() -> controller.download(unknown))
                .isInstanceOf(InvoiceException.class)
                .satisfies(InvoiceControllerTest::assertNotFound);
    }

    @Test
    void bookingInvoice_byInvoiceCustomer_returnsSummary() {
        UUID customerId = UUID.randomUUID();
        Invoice invoice = invoice(customerId, UUID.randomUUID());
        InvoiceSummary summary = summaryOf(invoice);
        authenticate(customerId, "CUSTOMER");
        when(queryService.findForBooking(invoice.getBookingId())).thenReturn(invoice);
        when(queryService.toSummary(invoice)).thenReturn(summary);

        assertThat(controller.bookingInvoice(invoice.getBookingId())).isEqualTo(summary);
    }

    @Test
    void bookingInvoice_ofAnotherCustomersBooking_isNotFound() {
        Invoice invoice = invoice(UUID.randomUUID(), UUID.randomUUID());
        authenticate(UUID.randomUUID(), "CUSTOMER");
        when(queryService.findForBooking(invoice.getBookingId())).thenReturn(invoice);

        assertThatThrownBy(() -> controller.bookingInvoice(invoice.getBookingId()))
                .isInstanceOf(InvoiceException.class)
                .satisfies(InvoiceControllerTest::assertNotFound);
        verify(queryService, never()).toSummary(any());
    }
}
