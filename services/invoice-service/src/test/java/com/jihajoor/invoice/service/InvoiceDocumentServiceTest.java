package com.jihajoor.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jihajoor.invoice.config.InvoiceProperties;
import com.jihajoor.invoice.domain.Invoice;
import com.jihajoor.invoice.domain.InvoiceData;
import com.jihajoor.invoice.support.TestPorts.AlwaysFailingPdfGenerator;
import com.jihajoor.invoice.support.TestPorts.FixedDetailsPort;
import com.jihajoor.invoice.support.TestPorts.FlakyPdfGenerator;
import com.jihajoor.invoice.support.TestPorts.RecordingStoragePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link InvoiceDocumentService}: the stored PDF is served as-is, and a PDF missing
 * from storage (the in-memory adapter after a restart) is re-rendered from the persisted invoice
 * record and written back.
 */
class InvoiceDocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private final RecordingStoragePort storage =
            new RecordingStoragePort(Clock.fixed(NOW, ZoneOffset.UTC));
    private final InvoiceProperties properties = new InvoiceProperties();

    private static Invoice invoice() {
        UUID customer = UUID.randomUUID();
        return Invoice.create("INV-2026-10-000007", UUID.randomUUID(), UUID.randomUUID(), customer,
                UUID.randomUUID(), "invoices/" + customer + "/INV-2026-10-000007.pdf",
                new BigDecimal("590.00"), new BigDecimal("118.00"), new BigDecimal("472.00"), NOW);
    }

    @Test
    void storedPdf_isServedWithoutRendering() {
        Invoice invoice = invoice();
        storage.store(invoice.getS3Key(), "STORED".getBytes());
        FlakyPdfGenerator generator = new FlakyPdfGenerator(0);
        InvoiceDocumentService service =
                new InvoiceDocumentService(storage, new FixedDetailsPort(), generator, properties);

        assertThat(service.pdf(invoice)).isEqualTo("STORED".getBytes());
        assertThat(generator.callCount()).isZero();
    }

    @Test
    void missingPdf_isReRenderedFromTheRecord_andStoredBack() {
        Invoice invoice = invoice();
        AtomicReference<InvoiceData> rendered = new AtomicReference<>();
        InvoiceDocumentService service = new InvoiceDocumentService(storage, new FixedDetailsPort(),
                data -> {
                    rendered.set(data);
                    return ("PDF:" + data.invoiceNumber()).getBytes();
                }, properties);

        byte[] pdf = service.pdf(invoice);

        assertThat(pdf).isEqualTo("PDF:INV-2026-10-000007".getBytes());
        assertThat(rendered.get().totalAmount()).isEqualByComparingTo("590.00");
        assertThat(rendered.get().invoiceDate()).isEqualTo(NOW);
        assertThat(rendered.get().paymentMethod())
                .isEqualTo(InvoiceDocumentService.RE_RENDERED_PAYMENT_METHOD);
        assertThat(storage.load(invoice.getS3Key())).hasValue(pdf);
    }

    @Test
    void failedReRender_isServiceUnavailable() {
        Invoice invoice = invoice();
        InvoiceDocumentService service = new InvoiceDocumentService(storage, new FixedDetailsPort(),
                new AlwaysFailingPdfGenerator(), properties);

        assertThatThrownBy(() -> service.pdf(invoice))
                .isInstanceOf(InvoiceException.class)
                .satisfies(e -> {
                    InvoiceException ex = (InvoiceException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getErrorCode()).isEqualTo("INVOICE_DOCUMENT_UNAVAILABLE");
                });
    }
}
