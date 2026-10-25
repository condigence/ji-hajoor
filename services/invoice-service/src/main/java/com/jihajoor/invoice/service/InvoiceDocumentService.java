package com.jihajoor.invoice.service;

import java.util.Optional;

import com.jihajoor.invoice.config.InvoiceProperties;
import com.jihajoor.invoice.details.InvoiceDetails;
import com.jihajoor.invoice.details.InvoiceDetailsPort;
import com.jihajoor.invoice.domain.Invoice;
import com.jihajoor.invoice.domain.InvoiceData;
import com.jihajoor.invoice.pdf.InvoicePdfException;
import com.jihajoor.invoice.pdf.PdfGeneratorPort;
import com.jihajoor.invoice.storage.InvoiceStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Serves the PDF bytes of an already-generated invoice for {@code GET /invoices/{id}/download}.
 *
 * <p>The PDF stored at generation time is returned when storage still has it. The default storage
 * adapter is in-memory, so after a restart the object is gone while the invoice row survives; the
 * document is then rendered again from the persisted invoice record and the same details source
 * used at generation time, and written back so later downloads are served from storage. The
 * payment method is not persisted on the invoice row, so a re-rendered document labels it
 * generically; every amount comes from the persisted record.
 *
 * <p>This class performs no authorization: the controller applies the ownership check first.
 */
@Service
public class InvoiceDocumentService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceDocumentService.class);

    /** Payment-method label on a re-rendered document (the method is not persisted). */
    static final String RE_RENDERED_PAYMENT_METHOD = "As recorded at payment";

    private final InvoiceStoragePort storagePort;
    private final InvoiceDetailsPort invoiceDetailsPort;
    private final PdfGeneratorPort pdfGeneratorPort;
    private final InvoiceProperties properties;

    public InvoiceDocumentService(InvoiceStoragePort storagePort,
                                  InvoiceDetailsPort invoiceDetailsPort,
                                  PdfGeneratorPort pdfGeneratorPort,
                                  InvoiceProperties properties) {
        this.storagePort = storagePort;
        this.invoiceDetailsPort = invoiceDetailsPort;
        this.pdfGeneratorPort = pdfGeneratorPort;
        this.properties = properties;
    }

    /**
     * @return the invoice's PDF bytes
     * @throws InvoiceException 503 {@code INVOICE_DOCUMENT_UNAVAILABLE} when the stored PDF is gone
     *                          and re-rendering it fails
     */
    public byte[] pdf(Invoice invoice) {
        Optional<byte[]> stored = storagePort.load(invoice.getS3Key());
        if (stored.isPresent()) {
            return stored.get();
        }
        log.info("Stored PDF missing for invoice {}; re-rendering from the invoice record",
                invoice.getInvoiceNumber());
        byte[] pdf;
        try {
            pdf = pdfGeneratorPort.render(toInvoiceData(invoice));
        } catch (InvoicePdfException e) {
            log.warn("Re-rendering invoice {} failed: {}", invoice.getInvoiceNumber(), e.toString());
            throw new InvoiceException(HttpStatus.SERVICE_UNAVAILABLE, "INVOICE_DOCUMENT_UNAVAILABLE",
                    "invoice document is temporarily unavailable");
        }
        try {
            storagePort.store(invoice.getS3Key(), pdf);
        } catch (RuntimeException e) {
            // Serving the document matters more than caching it; the next download re-renders.
            log.warn("Could not store re-rendered invoice {}: {}", invoice.getInvoiceNumber(),
                    e.toString());
        }
        return pdf;
    }

    private InvoiceData toInvoiceData(Invoice invoice) {
        InvoiceDetails details = invoiceDetailsPort.resolve(
                invoice.getBookingId(), invoice.getCustomerId(), invoice.getProviderId());
        return new InvoiceData(
                invoice.getInvoiceNumber(),
                invoice.getGeneratedAt(),
                invoice.getBookingId(),
                invoice.getPaymentId(),
                invoice.getCustomerId(),
                invoice.getProviderId(),
                details.customerName(),
                details.customerAddress(),
                details.providerName(),
                details.providerVerified(),
                details.serviceDescription(),
                details.lineItems(),
                details.taxAmount(),
                properties.getTaxIdentifier(),
                details.discountAmount(),
                invoice.getGrossAmount(),
                RE_RENDERED_PAYMENT_METHOD);
    }
}
