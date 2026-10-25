package com.jihajoor.invoice.api;

import java.util.List;
import java.util.UUID;

import com.jihajoor.invoice.api.dto.InvoiceSummary;
import com.jihajoor.invoice.api.dto.ProviderEarningsStatement;
import com.jihajoor.invoice.domain.Invoice;
import com.jihajoor.invoice.service.InvoiceDocumentService;
import com.jihajoor.invoice.service.InvoiceQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface for the Invoice Service.
 *
 * <ul>
 *   <li>{@code GET /invoices/customers/{customerId}} — customer invoice history within the
 *       24-month retention window (Requirement 13.5).</li>
 *   <li>{@code GET /invoices/providers/{providerId}/statements/{year}/{month}} — provider monthly
 *       earnings statement, available on the first day of the following month
 *       (Requirement 13.6).</li>
 *   <li>{@code GET /invoices/bookings/{bookingId}} — the invoice summary for one booking, which
 *       the customer app looks up once the booking is paid.</li>
 *   <li>{@code GET /invoices/{invoiceId}/download} — the invoice PDF itself, as an attachment.</li>
 * </ul>
 *
 * <p>Authentication is enforced by the shared filters; fine-grained role checks are applied by the
 * shared {@code RbacEnforcementFilter} (Task 4) from the rules in {@code InvoiceRbacConfig}. Roles
 * alone cannot express ownership, so each handler additionally asserts via {@link CallerIdentity}
 * that the caller is the customer/provider named in the path (or a party to the invoice), or is
 * staff. The two invoice-scoped routes answer a failed ownership check with the same 404 as an
 * unknown id, so invoice and booking ids cannot be probed.
 */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceQueryService invoiceQueryService;
    private final InvoiceDocumentService invoiceDocumentService;
    private final CallerIdentity callerIdentity;

    public InvoiceController(InvoiceQueryService invoiceQueryService,
                             InvoiceDocumentService invoiceDocumentService,
                             CallerIdentity callerIdentity) {
        this.invoiceQueryService = invoiceQueryService;
        this.invoiceDocumentService = invoiceDocumentService;
        this.callerIdentity = callerIdentity;
    }

    @GetMapping("/customers/{customerId}")
    public List<InvoiceSummary> customerHistory(@PathVariable UUID customerId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        callerIdentity.requireSelfOrStaff(customerId);
        return invoiceQueryService.customerHistory(customerId, page, size);
    }

    @GetMapping("/providers/{providerId}/statements/{year}/{month}")
    public ProviderEarningsStatement providerStatement(@PathVariable UUID providerId,
                                                        @PathVariable int year,
                                                        @PathVariable int month) {
        callerIdentity.requireSelfOrStaff(providerId);
        return invoiceQueryService.providerMonthlyStatement(providerId, year, month);
    }

    @GetMapping("/bookings/{bookingId}")
    public InvoiceSummary bookingInvoice(@PathVariable UUID bookingId) {
        Invoice invoice = invoiceQueryService.findForBooking(bookingId);
        callerIdentity.requireInvoicePartyOrStaff(invoice.getCustomerId(), invoice.getProviderId());
        return invoiceQueryService.toSummary(invoice);
    }

    @GetMapping("/{invoiceId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID invoiceId) {
        Invoice invoice = invoiceQueryService.findInvoice(invoiceId);
        callerIdentity.requireInvoicePartyOrStaff(invoice.getCustomerId(), invoice.getProviderId());
        byte[] pdf = invoiceDocumentService.pdf(invoice);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(invoice.getInvoiceNumber() + ".pdf")
                        .build()
                        .toString())
                // Personal financial document: never cache in shared caches or on disk.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(pdf);
    }
}
